import sys, re, json, html, concurrent.futures as cf
import requests
import xml.etree.ElementTree as ET
UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 TTSing/1.0"
H={"User-Agent":UA,"Accept":"application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8, */*;q=0.5","Accept-Language":"en,pt-BR;q=0.8"}
AD_OLD=re.compile(r"oferta|promo[cç]|patrocin|publieditorial|cupom|cupons|black friday|desconto|\bdeals?\b|sponsor|presented by|\bsale\b|% off|discount|coupon|gift guide|best .* (deals|to buy)|vale a pena comprar|melhores .*(celulares|notebooks|ofertas)|\bbuy\b|amazon prime|prime day|review:|onde comprar|preço|barato|menor preço|mercado livre|achadinho|\bsorteio\b|advertis|paid post|partner content|branded|\bbrandvoice|\bpress release|comunicado", re.I)
import time
AD=re.compile(r"oferta|promo[cç][aã]o|promoções|cupom|cupons|desconto|patrocin|publieditorial|black friday|sorteio|% off|\bdeals?\b|coupon|promo code|sponsor|gift guide|prime day|\bsale\b|\bbest .{0,40} (of 20|to buy)|webinar|whitepaper|\bad buy\b", re.I)
CATAD=re.compile(r'coupon|deal|sponsor|webinar|whitepaper|advertis|publi|patrocin|oferta|guia de compras|buying guide',re.I)
def get(url, **kw):
    for a in range(3):
        try:
            r=requests.get(url, headers=H, timeout=25, allow_redirects=True, **kw)
        except Exception as e:
            return e
        if r.status_code==429 and a<2: time.sleep(6); continue
        return r
def strip_ns(t): return t.split('}')[-1]
def parse(text):
    items=[]
    try:
        root=ET.fromstring(text.encode('utf-8') if isinstance(text,str) else text)
    except Exception as e:
        # try bytes
        return None, str(e)
    for el in root.iter():
        n=strip_ns(el.tag)
        if n in('item','entry'):
            d={'title':'','link':'','cats':[],'date':'','body':''}
            for c in el:
                cn=strip_ns(c.tag)
                if cn=='title': d['title']=(c.text or '').strip()
                elif cn=='link':
                    d['link']=c.attrib.get('href') or (c.text or '').strip() if not d['link'] or c.attrib.get('rel')=='alternate' else d['link']
                elif cn in('category','subject'):
                    d['cats'].append(c.attrib.get('term') or (c.text or '').strip())
                elif cn in('pubDate','published','updated','date'): d['date']=d['date'] or (c.text or '')
                elif cn in('encoded','content'):
                    t=c.text or ''
                    if not t: t=''.join(ET.tostring(x,encoding='unicode') for x in c)
                    d['body']=t if len(t)>len(d['body']) else d['body']
                elif cn in('description','summary'):
                    t=c.text or ''
                    d['body']=t if len(t)>len(d['body']) else d['body']
            items.append(d)
    return items,None
def textlen(h):
    h=re.sub(r"(?is)<(script|style|noscript|nav|header|footer|aside|form)\b.*?</\1>","",h)
    ps=re.findall(r"(?is)<p\b[^>]*>(.*?)</p>",h)
    t=" ".join(html.unescape(re.sub(r"<[^>]+>","",p)).strip() for p in ps)
    t=re.sub(r"\s+"," ",t)
    return len(t)
def audit(name,url,fetch_pages=True):
    r=get(url)
    out={'name':name,'url':url}
    if isinstance(r,Exception): out['err']=str(r)[:80]; return out
    out['status']=r.status_code; out['ctype']=r.headers.get('content-type','')[:30]
    if r.status_code!=200: return out
    r.encoding=r.encoding or 'utf-8'
    items,err=parse(r.content)
    if items is None: out['err']='parse:'+err[:60]; return out
    out['n']=len(items)
    last=items[:30]
    ads=[i for i in last if AD.search(i['title']) or any(CATAD.search(c) for c in i['cats'])]
    allads=[i for i in items if AD.search(i['title']) or any(CATAD.search(c) for c in i['cats'])]
    out['allads']=f"{len(allads)}/{len(items)}"
    out['ads']=len(ads); out['ad_titles']=[a['title'][:70] for a in ads[:6]]
    SP=re.compile(r'sponsored by|this post is sponsored|sponsored post|paid partnership|presented by|publieditorial|conte[uú]do patrocinado|patrocinado por|in partnership with|affiliate|link de afiliado|comiss[aã]o de vendas|ganhar uma comiss|use o cupom|usando o cupom',re.I)
    out['bodysp']=sum(1 for i in last if SP.search(i['body']))
    out['newest']=last[0]['date'] if last else ''
    out['oldest']=last[-1]['date'] if last else ''
    out['sample_titles']=[i['title'][:70] for i in last[:4]]
    cats={}
    for i in last:
        for c in i['cats']: cats[c]=cats.get(c,0)+1
    out['topcats']=sorted(cats.items(),key=lambda x:-x[1])[:6]
    out['inline']=[textlen(i['body']) if '<p' in i['body'] else len(re.sub(r'<[^>]+>','',i['body'])) for i in last[:3]]
    if fetch_pages:
        pl=[]
        for i in last[:3]:
            if not i['link']: pl.append('nolink'); continue
            p=get(i['link'])
            if isinstance(p,Exception): pl.append('ERR'); continue
            if p.status_code!=200: pl.append(str(p.status_code)); continue
            pl.append(textlen(p.text))
        out['page']=pl
    return out
if __name__=='__main__':
    feeds=[l.strip().split('|') for l in open(sys.argv[1],encoding='utf-8') if l.strip() and not l.startswith('#')]
    with cf.ThreadPoolExecutor(6) as ex:
        res=list(ex.map(lambda f: audit(f[0],f[1]),feeds))
    for o in res:
        print(f"## {o['name']} | {o['url']}")
        print(f"   status={o.get('status')} err={o.get('err')} n={o.get('n')} ads30={o.get('ads')} all={o.get('allads')} bodysponsor={o.get('bodysp')} inline={o.get('inline')} page={o.get('page')}")
        print(f"   newest={o.get('newest')} oldest={o.get('oldest')}")
        if o.get('ad_titles'): print("   AD:",o['ad_titles'])
        print("   cats:",o.get('topcats'))
        print("   titles:",o.get('sample_titles'))

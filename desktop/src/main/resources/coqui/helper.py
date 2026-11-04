"""TTSing's Coqui helper: keeps one Coqui TTS model loaded and speaks sentences for the app.

The app starts it with the Python that CoquiSetup installed and talks to it over pipes:

    app -> helper   one JSON object per line: {"text": "A sentence."}
    helper -> app   "READY <sample rate>\n" once the model is loaded, then for each sentence
                    "AUDIO <n>\n" and n little-endian float32 samples, or "ERROR <reason>\n"

`--download` fetches the model and exits, printing "DOWNLOADED". Coqui prints its own progress
on stdout, so the real stdout is kept for the protocol and everything else goes to stderr.
"""
import json
import os
import sys

MODEL = "tts_models/en/ljspeech/tacotron2-DDC"

protocol = os.fdopen(os.dup(sys.stdout.fileno()), "wb")
os.dup2(sys.stderr.fileno(), sys.stdout.fileno())


def send(line: str, payload: bytes = b"") -> None:
    protocol.write(line.encode() + b"\n" + payload)
    protocol.flush()


def main() -> None:
    import numpy as np
    import torch
    from TTS.api import TTS

    # More threads don't make Tacotron's step-by-step decoder any faster.
    torch.set_num_threads(4)
    tts = TTS(MODEL, progress_bar=False)
    if "--download" in sys.argv:
        send("DOWNLOADED")
        return
    tts.tts("Ready.")  # the first sentence through a fresh model is the slow one
    send(f"READY {tts.synthesizer.output_sample_rate}")

    for line in sys.stdin:
        try:
            text = json.loads(line)["text"].strip()
            audio = np.asarray(tts.tts(text, split_sentences=False), dtype="<f4") if text else np.zeros(0, "<f4")
            send(f"AUDIO {audio.size}", audio.tobytes())
        except Exception as e:  # one bad sentence must not end the helper
            send("ERROR " + " ".join(str(e).split())[:200])


if __name__ == "__main__":
    main()

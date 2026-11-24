# Sound prompts

## vocaguard-rec-notice.wav

Caller/user recording-consent notice, played by `extensions.conf` before any call
audio is captured. Installed on the server at
`/usr/share/asterisk/sounds/vocaguard-rec-notice.wav` (8 kHz, 16-bit, mono).

Text: "This call may be recorded and monitored for fraud protection. By staying on
the line, you consent to this."

Generated 2026-11-24 with [Piper](https://github.com/rhasspy/piper) using the voice
`en_US-libritts_r-medium`, speaker 0, length scale 1.1, then converted with
`sox ... -r 8000 -c 1 -e signed-integer -b 16 ... gain -n -1`.

Voice model trained on the LibriTTS-R corpus (Koizumi et al., 2023,
http://www.openslr.org/141/), licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

To regenerate:

```sh
echo "<text>" | python -m piper -m en_US-libritts_r-medium.onnx -s 0 --length-scale 1.1 -f notice.wav
sox notice.wav -r 8000 -c 1 -e signed-integer -b 16 vocaguard-rec-notice.wav gain -n -1
```

Check the result at phone quality before installing: transcribe it with the server's
Vosk English model and confirm every word comes back correctly.

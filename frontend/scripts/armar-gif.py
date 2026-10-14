"""Arma docs/img/recorrido.gif con los cuadros de scripts/recorrido-gif.mjs (requiere Pillow)."""
from pathlib import Path

from PIL import Image

frames_dir = Path(__file__).resolve().parent.parent / 'test-results' / 'recorrido'
out = Path(__file__).resolve().parents[2] / 'docs' / 'img' / 'recorrido.gif'
paths = sorted(frames_dir.glob('*.png'))
if not paths:
    raise SystemExit('No hay cuadros: ejecute primero scripts/recorrido-gif.mjs')

width = 960
frames = []
for p in paths:
    img = Image.open(p).convert('RGB')
    img = img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)
    frames.append(img.convert('P', palette=Image.ADAPTIVE, colors=128))

durations = [1600 if i in (0, len(frames) - 1) else 1100 for i in range(len(frames))]
frames[0].save(out, save_all=True, append_images=frames[1:], duration=durations, loop=0, optimize=True)
print(f'{out} · {len(frames)} cuadros · {out.stat().st_size // 1024} KB')

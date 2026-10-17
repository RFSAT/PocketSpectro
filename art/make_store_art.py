#!/usr/bin/env python3
"""Renders the Google Play icon and feature graphic from icon.svg.  pip install cairosvg pillow
   python3 art/make_store_art.py   ->  art/play_icon_512.png, art/feature_graphic_1024x500.png"""
import os, re, io
import cairosvg
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
icon_svg = open(os.path.join(HERE, 'icon.svg')).read()

# Play icon: the adaptive icon's visible 72 dp (18..90 of 108) as a full-bleed 512 px square.
png = cairosvg.svg2png(bytestring=icon_svg.replace('viewBox="0 0 108 108"', 'viewBox="18 18 72 72"').encode(),
                       output_width=512, output_height=512)
icon = Image.open(io.BytesIO(png)).convert('RGB')
icon.save(os.path.join(HERE, 'play_icon_512.png'))

# Feature graphic: the icon's spectrum, enlarged on the right; name and tagline on the left.
paths = re.findall(r' d="([^"]+)"', icon_svg)
fill_d, line_d = paths[0], paths[1]
X0, X1, BASE, SY = 575, 990, 405, 6.6
SX = (X1 - X0) / 54.0

def tr(d):
    out, nums = [], []
    for t in re.findall(r'[MLCZ]|-?\d+\.?\d*', d):
        if t in 'MLCZ':
            out.append(t)
            continue
        nums.append(float(t))
        if len(nums) == 2:
            x, y = nums
            out.append(f'{X0 + (x - 27) * SX:.1f},{BASE - (74 - y) * SY:.1f}')
            nums = []
    return ' '.join(out)

stops = re.search(r'<linearGradient id="spec"[^>]*>(.*?)</linearGradient>', icon_svg, re.S).group(1)
grid = ''.join(f'<line x1="{X0}" y1="{y}" x2="{X1}" y2="{y}" stroke="#FFFFFF" stroke-opacity="0.07" stroke-width="1.5"/>'
               for y in range(125, 400, 70))
grid += ''.join(f'<line x1="{x:.0f}" y1="110" x2="{x:.0f}" y2="{BASE}" stroke="#FFFFFF" stroke-opacity="0.07" stroke-width="1.5"/>'
                for x in [X0 + i * (X1 - X0) / 6 for i in range(7)])
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
<defs>
 <radialGradient id="bg" cx="760" cy="160" r="780" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#2B3137"/><stop offset="1" stop-color="#0B0D0F"/></radialGradient>
 <linearGradient id="spec" x1="{X0}" y1="0" x2="{X1}" y2="0" gradientUnits="userSpaceOnUse">{stops}</linearGradient>
</defs>
<rect width="1024" height="500" fill="url(#bg)"/>
{grid}
<path d="{tr(fill_d)}" fill="url(#spec)"/>
<path d="{tr(line_d)}" fill="none" stroke="#FFFFFF" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>
<line x1="{X0}" y1="{BASE + 20}" x2="{X1}" y2="{BASE + 20}" stroke="#FFFFFF" stroke-opacity="0.5" stroke-width="5" stroke-linecap="round"/>
<text x="62" y="282" font-family="Inter" font-weight="700" font-size="62" fill="#FFFFFF" letter-spacing="-1">PocketSpectro</text>
<text x="64" y="328" font-family="Inter" font-weight="500" font-size="27" fill="#C9D1D9">Portable spectroscopy over USB</text>
<text x="64" y="380" font-family="Inter" font-weight="500" font-size="20" fill="#FF8A65">Live spectra · Logging · GPS map · Fire detection</text>
</svg>'''
fg = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=1024, output_height=500))).convert('RGB')
small = icon.resize((112, 112), Image.LANCZOS)
mask = Image.new('L', (448, 448), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, 447, 447], radius=120, fill=255)
fg.paste(small, (64, 96), mask.resize((112, 112), Image.LANCZOS))
fg.save(os.path.join(HERE, 'feature_graphic_1024x500.png'))
print('play_icon_512.png', icon.size, '| feature_graphic_1024x500.png', fg.size)

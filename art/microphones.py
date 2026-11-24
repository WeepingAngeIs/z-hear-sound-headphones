"""Build the 22-pixel microphone badges shared by vanilla font and HUD rendering.

Each microphone family has one bitmap atlas. HUD cells and name-tag glyphs share the same pixels.
"""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageColor

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/mobsear"
FAMILIES = ["uwu", "emo", "vanilla", "amethyst"]
COLORS = ["auto", "rose", "gold", "mint", "aqua", "violet"]
ACCENTS = dict(rose="#ff87b9", gold="#ffd16a", mint="#9bf1b8", aqua="#74eafa", violet="#d0a3ff")
THEMES = {
    "uwu": ("#32172a", "#78435e", "#ff91bc"),
    "emo": ("#171923", "#444256", "#b9b1d1"),
    "vanilla": ("#231f16", "#5b5240", "#eac880"),
    "amethyst": ("#24172e", "#624579", "#cf8fff"),
}
SIZE = 22
FRAMES = 13  # idle, mute, deaf, quiet/loud lit, then four frames for each speaking level.

def tint(color, target, percent):
    a, b = ImageColor.getrgb(color), ImageColor.getrgb(target)
    return tuple(round(x + (y-x)*percent) for x, y in zip(a, b))

def badge(family, accent_name, frame, framed=True):
    shadow, rim, native = THEMES[family]
    accent = ACCENTS.get(accent_name, native)
    lit = frame >= 3
    loud = frame == 4 or frame >= 9
    motion = (frame - 5) % 4 if frame >= 5 else None
    ink = ImageColor.getrgb(shadow)
    body = tint(accent, "#ffffff" if lit else rim, (0.22 if loud else 0.08) if lit else 0.45)
    light = tint(accent, "#ffffff", 0.67 if lit else 0.42)
    mid = tint(accent, rim, 0.28 if lit else 0.65)
    pic = Image.new("RGBA", (SIZE, SIZE))
    canvas = ImageDraw.Draw(pic)
    if framed:
        canvas.polygon([(1,1),(19,1),(20,2),(20,19),(19,20),(1,20),(0,19),(0,2)], fill=(*ink,225))
        canvas.line([(1,3),(1,2),(18,2)],fill=rim)
    mic = Image.new("RGBA",(SIZE,SIZE)); d = ImageDraw.Draw(mic)
    d.line([(5,11),(5,13),(7,15),(12,15),(14,13),(14,11)], fill=light, width=1)
    d.rectangle((9,15,10,17),fill=mid)
    d.rectangle((7,18,12,18),fill=light)
    d.rectangle((8,5,11,12), fill=body)
    d.rectangle((7,6,12,11), fill=body)
    d.line([(8,5),(11,5)],fill=light)
    if family=="uwu":
        d.polygon([(6,4),(9,6),(6,7)],fill=accent)
        d.polygon([(13,4),(13,7),(10,6)],fill=accent)
        for x in (8,11): d.point((x,8),fill=ink)
        d.point((9,10),fill=ink); d.point((10,10),fill=ink)
        d.point((7,10),fill=light); d.point((12,10),fill=light)
    elif family=="emo":
        d.polygon([(6,5),(13,4),(13,6),(11,7),(10,9),(9,7),(7,7)],fill=rim)
        d.line((8,10,10,10),fill=ink); d.point((11,9),fill=ink)
        d.point((12,11),fill=light)
    elif family=="vanilla":
        for y in (7,9,11): d.line((8,y,11,y),fill=ink)
        d.line((7,7,7,11),fill=light)
    else:
        d.polygon([(9,3),(13,6),(12,10),(10,13),(6,9),(7,6)],fill=mid)
        d.polygon([(9,3),(10,7),(6,9),(7,6)],fill=light)
        d.polygon([(10,7),(13,6),(10,13)],fill=body)
        d.point((10,5),fill="#ffffff")
    dx = (0,1,0,-1)[motion] if loud and motion is not None else 0
    dy = (0,-1,0,0)[motion] if motion is not None else 0
    pic.alpha_composite(mic,(dx,dy)); d=ImageDraw.Draw(pic)
    if frame == 1:  # Muted: a contrasting diagonal crosses the microphone.
        d.line((3,16,15,4),fill=ink,width=3)
        d.line((3,16,15,4),fill="#ffaaa8",width=1)
    elif frame == 2:  # Deafened: interrupt the two ear cups with a separate short slash.
        d.line([(3,11),(3,9),(5,6),(7,4),(12,4),(15,6),(16,9),(16,12)],fill=light,width=1)
        d.rectangle((3,10,5,13),fill="#ffd07b");d.rectangle((14,10,16,13),fill="#ffd07b")
        d.line((12,17,18,11),fill=ink,width=3);d.line((12,17,18,11),fill="#ffd07b")
    if lit:
        d.rectangle((1,18,1,18),fill=accent)
        d.line((2,19,3,19),fill=accent)
        if loud: d.line((2,3,4,3),fill=light)
    if motion is not None:
        for n in range(2 if loud else 1):
            step=(motion + n * 2)%4
            x=16 + n + (1 if step > 1 else 0); y=11 - step * 2 - n
            d.point((x,y),fill=light if n else accent)
            if family=="uwu" and (step==1 or step==2):
                d.point((x+2,y),fill=light);d.point((x+1,y+1),fill=accent)
            elif family=="amethyst" and step==1:
                d.line((x,y-1,x,y+1),fill=light);d.line((x-1,y,x+1,y),fill=accent)
            elif family=="emo" and step==1: d.point((x+1,y-1),fill=accent)
    return pic

def main():
    image_dir = ROOT/"textures/font"; image_dir.mkdir(parents=True,exist_ok=True)
    font_dir = ROOT/"font"; font_dir.mkdir(parents=True,exist_ok=True)
    providers=[]
    for family_index,family in enumerate(FAMILIES):
        image=Image.new("RGBA",(SIZE*FRAMES,SIZE*len(COLORS)))
        hud=Image.new("RGBA",image.size)
        rows=[]
        for accent_id,accent in enumerate(COLORS):
            row=""
            for frame in range(FRAMES):
                image.paste(badge(family,accent,frame),(frame*SIZE,accent_id*SIZE))
                hud.paste(badge(family,accent,frame,False),(frame*SIZE,accent_id*SIZE))
                row+=chr(0xe100+(family_index*len(COLORS)+accent_id)*FRAMES+frame)
            rows.append(row)
        image.save(image_dir/f"mic_{family}.png")
        hud_dir=ROOT/"textures/gui/mics"; hud_dir.mkdir(parents=True,exist_ok=True)
        hud.save(hud_dir/f"mic_{family}.png")
        providers.append(dict(type="bitmap",file=f"mobsear:font/mic_{family}.png",height=11,ascent=9,chars=rows))
    (font_dir/"microphones.json").write_text(json.dumps({"providers":providers},indent=2)+"\n",encoding="utf-8")

if __name__=="__main__": main()

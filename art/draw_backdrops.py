"""Paint eight-frame menu panoramas. Frame zero is also the reduced-motion still."""
from math import cos, sin, pi
from pathlib import Path
from random import Random
from PIL import Image, ImageColor, ImageDraw

DEST = Path(__file__).resolve().parents[1] / "src/main/resources/assets/mobsear/textures/gui/themes"
PINK = ["#43283e", "#63394a", "#925568", "#bd7186", "#ea97ac", "#ffd0cb", "#fff0cc"]
DARK = ["#171724", "#282738", "#3e384c", "#605165", "#8c708a", "#b296a1", "#ded1c3"]
GREEN = ["#193b38", "#285047", "#38664e", "#54805a", "#80a25e", "#bbbe70", "#f3d793"]
SPACE = ["#1e193a", "#322553", "#4a326c", "#704689", "#aa64aa", "#dd91c8", "#f4d8cf"]


def paint_frame(name, palette, scene, frame):
    rng = Random(name)
    im = Image.new("RGBA", (192, 96))
    d = ImageDraw.Draw(im)
    bg, deep, mid, base, light, shine, white = palette
    line, box, oval, poly = d.line, d.rectangle, d.ellipse, d.polygon

    def swing(offset):
        # Every step, including 7 -> 0, advances or retreats by just one native pixel.
        wave = (0, 1, 2, 1, 0, -1, -2, -1)
        return wave[(frame + offset) % 8] - wave[offset % 8]

    def lit(color, offset, amount):
        rgb = ImageColor.getrgb(color) if isinstance(color, str) else color[:3]
        change = round(amount * swing(offset) / 2)
        return tuple(max(0, min(255, channel + change)) for channel in rgb)

    def glint(x, y, offset, amount):
        if 0 <= x < im.width and 0 <= y < im.height:
            d.point((x, y), lit(im.getpixel((x, y)), offset, amount))

    def disc(x, y, r, color):
        oval((x-r, y-r, x+r, y+r), color)
    def stars(count):
        for i in range(count):
            x, y = rng.randrange(192), rng.randrange(82)
            color = [base, light, shine][i%3]
            d.point((x, y), lit(color, i, 22) if i % 3 == 0 else color)
            if i % 11 == 0:
                halo = lit(light, i, 15)
                line((x-2,y,x+2,y), halo); line((x,y-2,x,y+2), halo); d.point((x,y), white)
    def hills(y, color, step, amplitude):
        points = [(x, y + rng.randint(-amplitude,amplitude)) for x in range(-step, 193+step, step)]
        poly([(points[0][0],96), *points, (points[-1][0],96)], color)
    def hatched_water(y):
        box((0,y,191,95),deep)
        for i in range(110):
            x, v = rng.randrange(192), rng.randrange(y,96)
            length = rng.randint(2,13)
            if i % 3 == 0:
                x += swing(i)
                length += swing(i+1)
            line((x,v,x+max(1,length),v),[mid,base,mid][i%3])
    def blossom(x,y,s=1):
        for dx,dy in [(0,-2),(-2,-1),(2,-1),(-1,1),(1,1)]:
            box((x+dx*s,y+dy*s,x+(dx+1)*s,y+(dy+1)*s), rng.choice((light,shine)))
        box((x,y,x+s-1,y+s-1),white)
    def heart(x,y,s,c,split=False):
        pts=[(0,2),(2,0),(5,0),(7,2),(9,0),(12,0),(14,2),(14,5),(7,12),(0,5)]
        poly([(x+a*s,y+b*s) for a,b in pts],c)
        line((x+2*s,y+2*s,x+4*s,y+2*s),shine,s)
        if split:
            line([(x+8*s,y),(x+6*s,y+4*s),(x+9*s,y+5*s),(x+6*s,y+9*s),(x+7*s,y+12*s)],bg,max(1,s))
    def tree(x,y,r, fruit=False):
        box((x-2,y,x+2,y+23),deep)
        for dx,dy,k in [(-7,0,.7),(6,1,.78),(0,-4,1)]:
            disc(x+dx,y+dy,int(r*k),mid)
            disc(x+dx-1,y+dy-3,int(r*k*.85),base)
        if fruit:
            for dx,dy in [(-7,-3),(1,4),(6,-5)]:
                disc(x+dx,y+dy,2,"#e8a24e"); d.point((x+dx-1,y+dy-1),white)
            # A little dappled light runs across adjacent leaves while the fruit stays still.
            for j, (dx,dy) in enumerate([(-8,-6),(-1,-10),(6,-4),(2,-8)]):
                for k in range(5):
                    glint(x+dx+k,y+dy,x+j,13)
                    glint(x+dx+k-1,y+dy+1,x+j,10)
                    glint(x+dx+k-2,y+dy+2,x+j,7)
    def palm(x,y,h,flip=1):
        tx,ty=x+flip*7,y-h
        poly([(x-3,y),(x+3,y),(tx+2,ty),(tx-1,ty)],"#96754c")
        for j in range(5,h,5):
            line((x+flip*int(7*j/h)-2,y-j,x+flip*int(7*j/h)+1,y-j-1),deep)
        for side in (-1,1):
            for reach,lift in [(21,2),(15,-6),(11,9)]:
                tip=(tx+side*reach,ty+lift+3)
                poly([(tx,ty),(tx+side*(reach//2),ty-6),(tx+side*(reach-3),ty+lift-3),tip,
                      (tx+side*(reach//2),ty-2)],rng.choice((mid,base,light)))
        for dx in (-2,1): disc(tx+dx,ty+2,2,"#735640")
    def fern(x,y,dx,h):
        line((x,y,x+dx,y-h),light)
        for j in range(2,h-1,3):
            sx=x+dx*j//h; sy=y-j; length=max(2,int(7*(1-j/h)+2))
            for k in (-1,1):
                flex = swing(x//8+k) if j>h//2 else 0
                poly([(sx,sy),(sx+k*length+flex,sy-3),(sx+k*(length-2)+flex,sy-1)],base if k<0 else light)
    def planet(x,y,r,ring):
        disc(x,y,r,base)
        for yy in range(-r,r+1):
            half=int((r*r-yy*yy)**.5)
            if (yy+2*r)%8 in (1,2,3):
                line((x-half+1,y+yy,x+half-1,y+yy),light)
        if ring:
            pts=[(x+int(cos(t*pi/36)*r*1.8), y+int(sin(t*pi/36)*r*.32-cos(t*pi/36)*r*.46)) for t in range(73)]
            for i in list(range(36))+list(range(36,72)):
                if i<36 or abs(pts[i][0]-x)>r-1: line([pts[i],pts[i+1]],shine,2)

    box((0,0,191,95),bg)
    if scene == "lotus":
        disc(142,16,10,shine); hills(41,mid,17,6); hatched_water(43)
        # Water around the reflected moon changes brightness in staggered patches.
        for band, (x,y,w) in enumerate([(132,48,17),(143,54,12),(125,60,20),(139,69,13),(132,77,19)]):
            for dx in range(w):
                glint(x+dx,y,band+dx//5,14)
                glint(x+dx,y+1,band+dx//5,10)
        for x,y,r in [(16,79,15),(55,67,10),(101,87,16),(154,64,9),(177,83,14),(28,52,5)]:
            oval((x-r,y-r//3,x+r,y+r//3), "#779276")
            poly([(x,y),(x+r,y-2),(x+r,y+2)],deep)
            for dx,dy in [(-6,-3),(6,-3),(-3,-6),(3,-6),(0,-8)]:
                poly([(x,y),(x+dx-2,y+dy),(x+dx,y+dy-2),(x+dx+2,y+dy)],shine if dx%3==0 else light)
            line((x-2,y,x+2,y),white)
    elif scene == "blossom":
        hills(78,deep,20,5)
        for x,y,dx,dy in [(-5,79,92,-59),(36,52,5,-35),(61,36,45,-9),(194,27,-47,-11)]:
            line((x,y,x+dx,y+dy),mid,5)
            for j in range(2,10):
                ax,ay=x+dx*j//10,y+dy*j//10
                bx,by=ax+rng.randint(-7,12),ay-rng.randint(3,11)
                # Only the outermost blossom twigs sway. The large branches remain anchored.
                if j % 3 == 1: bx += swing(j+dx); by += swing(j+dy+1)
                line((ax,ay,bx,by),base,2); blossom(bx,by,rng.choice([1,1,2]))
    elif scene == "petals":
        hills(78,deep,18,6); hills(87,mid,15,4)
        for y0 in [11,30,48]:
            pts=[(x,int(y0+9*sin(x/30)+x*.09)) for x in range(-5,198,3)]
            line(pts,mid)
        for i in range(80):
            x,y=rng.randrange(192),rng.randrange(87); s=rng.choice([1,1,2])
            if i % 3 == 0: x += swing(i); y += swing(i+1)
            poly([(x,y),(x+3*s,y-s),(x+2*s,y+s),(x,y+2*s)],rng.choice([base,light,shine]))
    elif scene == "hearts":
        hills(64,deep,16,6); hills(80,mid,14,4)
        for x in range(-2,198,13):
            h=rng.randrange(12,47); y=95-h; s=1 if h<30 else 2
            line((x,95,x+2,y),base,2)
            leaf = swing(x//13)
            poly([(x,84),(x-7+leaf,78),(x-2,79)],light); poly([(x,76),(x+7-leaf,70),(x+5,74)],base)
            heart(x-7*s,y-8*s,s,rng.choice([base,light]))
            for k in range(2*s,5*s): glint(x-7*s+k,y-6*s,x//13+1,14)
    elif scene == "skulls":
        for x in range(-11,192,36):
            box((x,23,x+28,94),mid); oval((x,10,x+28,49),mid)
            oval((x+4,15,x+24,50),deep); box((x+4,30,x+24,94),deep)
            y=53+(x%3)*5
            oval((x+6,y-7,x+22,y+7),shine); box((x+9,y+1,x+19,y+10),shine)
            box((x+8,y,x+12,y+3),bg); box((x+16,y,x+20,y+3),bg)
            poly([(x+14,y+3),(x+12,y+6),(x+15,y+6)],bg)
            for dx in [10,14,18]: line((x+dx,y+8,x+dx,y+10),mid)
            # Low moonlight picks up the outer stone arch and the brow of the fixed skull.
            for dx,dy in [(4,18),(5,17),(6,16),(7,16),(8,15),(9,15)]:
                glint(x+dx,dy,x//36,15)
            for dx in range(7,13): glint(x+dx,y-4,x//36+1,10)
            for dx in range(16,21): glint(x+dx,y-4,x//36+2,10)
        hills(87,bg,8,3)
    elif scene == "thorns":
        for offset in [11,40,70]:
            pts=[(x,int(offset+10*sin(x/25))) for x in range(-5,200,4)]
            line(pts,mid,3)
            for i in range(3,len(pts),4):
                x,y=pts[i]; poly([(x,y),(x+4,y-7),(x+2,y)],base)
                if i%3: continue
                disc(x,y-2,6,"#804452"); disc(x-1,y-3,4,"#a85b65")
                line([(x-3,y-3),(x,y-5),(x+2,y-3),(x,y-1),(x-1,y-2)],"#e09491")
                for k in range(4): glint(x-2+k,y-4,i//3+offset,17)
                for k in range(3): glint(x+k,y-1,i//3+offset+1,13)
    elif scene == "shards":
        for x,y,s in [(11,16,2),(52,49,2),(115,21,3),(165,61,1),(4,80,1),(78,8,1)]:
            heart(x,y,s,"#995a70",True)
            for j in range(4):
                sx,sy=x+7*s+rng.randint(-5,6),y+12*s+j*3
                sx += swing(j+x); sy += swing(j+x+1)
                poly([(sx,sy),(sx+3,sy-1),(sx+1,sy+3)],base)
    elif scene == "eclipse":
        stars(50); disc(139,36,25,mid); disc(139,36,22,light); disc(139,36,19,white); disc(137,35,19,bg)
        for t in range(32):
            angle = (t-8)*pi/24
            glint(round(139+21*cos(angle)),round(36+21*sin(angle)),t//5,18)
            glint(round(139+22*cos(angle)),round(36+22*sin(angle)),t//5,11)
        hills(75,deep,13,7); hills(86,mid,10,5)
        for x in [8,26,55,169,182]:
            box((x-3,64,x+3,90),bg); poly([(x-5,66),(x,51),(x+5,66)],bg)
    elif scene == "ferns":
        for x in range(-8,200,12):
            y=rng.randrange(0,26); box((x,0,x+rng.randint(2,5),81),deep); tree(x,y,12)
        hills(74,mid,10,5)
        for x in range(-3,195,8): fern(x,96,rng.randint(-9,9),rng.randint(19,42))
    elif scene == "grove":
        hills(61,mid,16,5)
        for x in range(0,195,22): tree(x,rng.randrange(30,42),13,True)
        hills(78,base,16,5)
        for x,y in [(13,79),(49,87),(104,75),(141,81),(183,88)]:
            poly([(x-12,y-7),(x+12,y-7),(x+8,y+2),(x,y+6),(x-8,y+2)],light)
            poly([(x-10,y-7),(x+10,y-7),(x+6,y),(x,y+3),(x-6,y)],"#e58a79")
            for dx in [-4,2,6]: d.point((x+dx,y-4+dx%3),deep)
    elif scene == "jungle":
        hills(36,mid,15,8); hills(56,base,12,4); box((113,50,135,82),"#52998e")
        for i,x in enumerate([115,119,129,133]):
            line((x,51,x,82),"#9ccdb0")
            for y in range(52,82):
                if y%9 in (0,1,2,3): glint(x,y,y//3+i,14)
        hatched_water(83)
        for x,y,h,f in [(6,88,48,1),(43,76,42,-1),(170,88,60,1),(189,73,38,-1)]: palm(x,y,h,f)
    elif scene == "beach":
        disc(148,23,12,shine); hatched_water(42)
        for y in range(64,96):
            edge=round(9*sin(y/15)+(y-64)*4.5)+swing(y//12)
            line((0,y,edge,y),"#cfaa76"); line((edge,y,edge+4,y),"#e9ddaa")
        palm(18,87,51,1); palm(44,80,32,-1)
    elif scene == "nebula":
        for i in range(650):
            x=rng.randrange(-12,205); y=58-25*sin(x/46)+rng.gauss(0,9); r=rng.choice([2,3,4,6])
            disc(x,int(y),r,rng.choice([deep,mid,mid,base]))
        for i in range(100):
            x=rng.randrange(192); y=58-25*sin(x/46)+rng.gauss(0,4)
            line((x,int(y),x+2,int(y)),lit(light,i,16) if i%4==0 else light)
        stars(108)
    elif scene == "rings":
        stars(102); planet(142,32,19,True); planet(46,71,12,True); planet(89,17,4,False)
        for x,y,r in [(142,32,19),(46,71,12)]:
            # Phase the tiny highlights along the front arc; all planet silhouettes are fixed.
            for t in range(9,33):
                angle=t*pi/36
                glint(x+int(cos(angle)*r*1.8),y+int(sin(angle)*r*.32-cos(angle)*r*.46),t//4,17)
    elif scene == "void":
        stars(85); x,y=126,47
        for r,c in [(25,mid),(21,base),(18,light),(16,shine),(14,bg)]: disc(x,y,r,c)
        for off,c in [(3,base),(1,light),(-1,shine)]:
            pts=[(v,int(y+(v-x)*-.19+off+3*sin((v-x)/15))) for v in range(63,187)]
            line(pts,c,2)
        oval((x-8,y-9,x+9,y+4),bg)
        for v in range(69,182):
            if abs(v-x)>16 and v%13 in (2,3,4,5,6):
                yy=int(y+(v-x)*-.19-1+3*sin((v-x)/15))
                glint(v,yy,v//11,18); glint(v,yy+1,v//11,11)
    elif scene == "stars":
        stars(150)
        for pts in [[(9,22),(24,12),(39,27),(53,22),(63,39)],[(122,61),(136,53),(151,65),(166,57),(180,68)]]:
            line(pts,mid)
            for x,y in pts: line((x-2,y,x+2,y),shine); line((x,y-2,x,y+2),shine)
        for x,y in [(91,24),(76,35)]:
            phase=x
            x += swing(phase); y += swing(phase+2)
            line((x-16,y+8,x,y),base,2); line((x-5,y+2,x,y),shine,2); d.point((x,y),white)
    else:
        raise ValueError(scene)
    # The texture itself retains alpha so the Minecraft frame still shows through.
    im.putalpha(208)
    return im


def paint(name, palette, scene):
    frames = [paint_frame(name, palette, scene, phase) for phase in range(8)]
    sheet = Image.new("RGBA",(192,768))
    for i, frame in enumerate(frames):
        sheet.paste(frame,(0,i*96))
    # Each scene uses a small hand-picked palette and one opacity across every phase.
    # Store exact palette entries with PNG tRNS, then verify the decoded RGBA pixels.
    rgb = sheet.convert("RGB")
    indexed = rgb.convert("P", palette=Image.Palette.ADAPTIVE, colors=len(rgb.getcolors()))
    indexed.info["transparency"] = bytes([208] * (indexed.getextrema()[1] + 1))
    assert indexed.convert("RGBA").tobytes() == sheet.tobytes()
    indexed.save(DEST / (name + ".png"), optimize=True)


if __name__ == "__main__":
    DEST.mkdir(parents=True, exist_ok=True)
    for family, pal, scenes in [
        ("uwu", PINK, ["lotus","blossom","petals","hearts"]),
        ("emo", DARK, ["skulls","thorns","shards","eclipse"]),
        ("vanilla", GREEN, ["ferns","grove","jungle","beach"]),
        ("amethyst", SPACE, ["nebula","rings","void","stars"]),
    ]:
        for scene in scenes:
            paint(family+"_"+scene, pal, scene)

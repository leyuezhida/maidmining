#requires -Version 5.1
<#
  女仆挖矿 (Maid Mining) · 封面 / 图标生成脚本
  ------------------------------------------------------------------
  主角：车万女仆 (Touhou Little Maid) 的「酒狐」wine_fox_maid
        金发、橙狐耳、琥珀眼、白上衣 + 暗红泡泡袖 + 藏青裙 + 白围裙，
        侧头别着一只狐狸面具，身后一条大橙尾巴。
  配色来源：TLM jar 内官方画作 assets/touhou_little_maid/textures/painting/wine_fox.png
            （脚本作者直接从该图取色；量化统计见 OPTIMIZATION.md 附录 F）

  产物：
    cover.png                        240x150  MC百科封面（替换旧图）
    src/main/resources/logo.png      128x128  模组列表图标（方形构图）
    publish/banner-1280x720.png      CurseForge / Modrinth 展示图
    publish/icon-512x512.png         CurseForge / Modrinth 项目头像

  用法：powershell -ExecutionPolicy Bypass -File make_cover.ps1
  说明：所有图形都是矢量绘制后超采样降采样得到，因此放大到 1280x720 依然干净。

  ⚠️ 编码：本文件必须保存为 UTF-8 **带 BOM**。Windows PowerShell 5.1 读无 BOM 的
     UTF-8 脚本时会按 ANSI 解码，中文注释会变成乱码并导致解析错误（
     "Unexpected token '}'"）。PowerShell 7+ 无此问题。
     若用编辑器另存后报解析错误，请确认 BOM 还在（文件头三字节 EF BB BF）。
#>
param([string]$OutDir = $PSScriptRoot)

Add-Type -AssemblyName System.Drawing

# ---------------- 调色板（取自官方画作） ----------------
$P = @{
    hairHi = 'FFD98A'; hair = 'F0BE58'; hairLo = 'C68F33'; hairLine = '8A5A20'
    skinHi = 'FBE7D4'; skin = 'F5DCC4'; skinLo = 'E0BFA2'; blush = 'EE8E86'
    earOut = 'E8A94A'; earIn = 'FFF1DE'; earEdge = 'A8681F'
    tail   = 'DC9633'; tailIn = 'F2C77E'; tailTip = 'FFF3E2'
    white  = 'FAF7F2'; whiteLo = 'DFD8CE'; whiteEdge = 'C6BCB0'
    red    = 'A32B22'; redLo = '7A1A14'; redHi = 'C44035'
    navy   = '262A42'; navyLo = '171A29'; navyHi = '3B4368'
    eye    = 'E06A20'; eyeLo = 'A8460C'; eyeDark = '46220E'
    iron   = 'CBD0D8'; ironLo = '98A0AA'; ironHi = 'F2F5F9'
    wood   = '9A6432'; woodLo = '6E4220'; woodHi = 'B87C42'
    bgTop  = '232833'; bgBot  = '12141A'
    stone  = '2C313C'; stoneB = '262B35'; stoneC = '333946'; seam = '191D25'
    diamond = '63E8DC'; gold = 'F7D954'; redstone = 'E03A2A'; emerald = '35D46A'; ironOre = 'D8AF93'
}

function C([string]$hex, [int]$a = 255) {
    $r = [Convert]::ToInt32($hex.Substring(0, 2), 16)
    $g = [Convert]::ToInt32($hex.Substring(2, 2), 16)
    $b = [Convert]::ToInt32($hex.Substring(4, 2), 16)
    return [System.Drawing.Color]::FromArgb($a, $r, $g, $b)
}
function Br([string]$hex, [int]$a = 255) { return (New-Object System.Drawing.SolidBrush (C $hex $a)) }
function Pn([string]$hex, [double]$w = 1, [int]$a = 255) { return (New-Object System.Drawing.Pen ((C $hex $a), [float]$w)) }

function Pf([object[]]$pairs) {
    $list = New-Object 'System.Collections.Generic.List[System.Drawing.PointF]'
    foreach ($p in $pairs) { $list.Add((New-Object System.Drawing.PointF([float]$p[0], [float]$p[1]))) }
    return $list.ToArray()
}
function Poly($g, [string]$hex, [object[]]$pairs, [int]$a = 255) {
    $b = Br $hex $a; $g.FillPolygon($b, (Pf $pairs)); $b.Dispose()
}
function Ell($g, [string]$hex, [double]$x, [double]$y, [double]$w, [double]$h, [int]$a = 255) {
    $b = Br $hex $a; $g.FillEllipse($b, [float]$x, [float]$y, [float]$w, [float]$h); $b.Dispose()
}
function NewPath { return (New-Object System.Drawing.Drawing2D.GraphicsPath) }
function FillPath2($g, [string]$hex, $path, [int]$a = 255) {
    $b = Br $hex $a; $g.FillPath($b, $path); $b.Dispose()
}
# 填充 + 描边：卡通描边能让小尺寸下的造型彼此分离（尾巴/头发/裙子不再糊成一坨）
function FillPathOutline($g, [string]$hex, $path, [string]$lineHex, [double]$w = 1.2) {
    $b = Br $hex; $g.FillPath($b, $path); $b.Dispose()
    $p = Pn $lineHex $w 210; $g.DrawPath($p, $path); $p.Dispose()
}

# 确定性伪随机（保证每次生成的图完全一致）
$script:seed = 20260920
function Rnd([double]$min = 0, [double]$max = 1) {
    $script:seed = ($script:seed * 1103515245 + 12345) -band 0x7FFFFFFF
    return $min + ($script:seed / 0x7FFFFFFF) * ($max - $min)
}

# =====================================================================
#  酒狐本体：局部坐标 (0,0)=双脚中心，向上为负 y，身高约 112 单位
# =====================================================================
function Draw-Jiuhu($g, [double]$bx, [double]$by, [double]$s) {
    $state = $g.Save()
    $g.TranslateTransform([float]$bx, [float]$by)
    $g.ScaleTransform([float]$s, [float]$s)

    # ---- 1. 尾巴（最底层，从身后甩向左上；加描边以免和头发糊在一起）----
    $t = NewPath
    $t.AddBezier(-8, -42, -28, -40, -42, -58, -44, -78)
    $t.AddBezier(-44, -78, -46, -98, -32, -108, -22, -101)
    $t.AddBezier(-22, -101, -12, -94, -8, -70, -8, -42)
    $t.CloseFigure()
    FillPathOutline $g $P.tail $t $P.hairLine 1.4
    $t.Dispose()
    $t2 = NewPath
    $t2.AddBezier(-11, -52, -28, -50, -42, -62, -44, -78)
    $t2.AddBezier(-44, -78, -46, -94, -32, -102, -22, -96)
    $t2.AddBezier(-22, -96, -16, -90, -12, -70, -11, -52)
    $t2.CloseFigure()
    FillPath2 $g $P.tailIn $t2
    $t2.Dispose()
    # 白色尾尖：顺着尾巴外缘的月牙形（原先是个圆，看起来像浮空的球）
    $t3 = NewPath
    $t3.AddBezier(-43, -80, -45, -96, -34, -106, -22, -101)
    $t3.AddBezier(-22, -101, -30, -95, -38, -88, -40, -78)
    $t3.CloseFigure()
    FillPath2 $g $P.tailTip $t3
    $t3.Dispose()

    # ---- 2. 后发（收窄 + 尖梢，避免变成一个大蛋形）----
    $bh = NewPath
    $bh.AddBezier(-19, -95, -27, -76, -28, -50, -23, -28)
    $bh.AddBezier(-23, -28, -19, -17, -12, -13, -8, -21)
    $bh.AddBezier(-8, -21, -4, -12, 4, -12, 8, -21)
    $bh.AddBezier(8, -21, 12, -13, 19, -17, 23, -28)
    $bh.AddBezier(23, -28, 28, -50, 27, -76, 19, -95)
    $bh.CloseFigure()
    FillPathOutline $g $P.hairLo $bh $P.hairLine 1.4
    $bh.Dispose()
    $bh2 = NewPath
    $bh2.AddBezier(-16, -94, -23, -76, -24, -52, -20, -32)
    $bh2.AddBezier(-20, -32, -16, -22, -11, -19, -7, -26)
    $bh2.AddBezier(-7, -26, -3, -18, 3, -18, 7, -26)
    $bh2.AddBezier(7, -26, 11, -19, 16, -22, 20, -32)
    $bh2.AddBezier(20, -32, 24, -52, 23, -76, 16, -94)
    $bh2.CloseFigure()
    FillPath2 $g $P.hair $bh2
    $bh2.Dispose()

    # ---- 3. 腿 / 袜 / 鞋 ----
    Poly $g $P.skin @(@(-10, -22), @(-2, -22), @(-2, -14), @(-10, -14))
    Poly $g $P.skin @(@(2, -22), @(10, -22), @(10, -14), @(2, -14))
    Poly $g $P.white @(@(-10, -15), @(-2, -15), @(-2, -4), @(-10, -4))
    Poly $g $P.white @(@(2, -15), @(10, -15), @(10, -4), @(2, -4))
    Poly $g $P.navyLo @(@(-11, -5), @(-1, -5), @(-1, 0), @(-11, 0))
    Poly $g $P.navyLo @(@(1, -5), @(11, -5), @(11, 0), @(1, 0))

    # ---- 4. 裙子（藏青 + 白色荷叶边）----
    $sk = NewPath
    $sk.AddBezier(-14, -44, -22, -34, -25, -24, -24, -18)
    $sk.AddBezier(-24, -18, -9, -13, 9, -13, 24, -18)
    $sk.AddBezier(24, -18, 25, -24, 22, -34, 14, -44)
    $sk.CloseFigure()
    FillPath2 $g $P.navy $sk
    $sk.Dispose()
    # 裙摆高光
    Poly $g $P.navyHi @(@(-20, -26), @(20, -26), @(19, -22), @(-19, -22)) 120
    # 荷叶边
    for ($i = -3; $i -le 3; $i++) {
        Ell $g $P.white ($i * 6.6 - 3.6) -19 7.6 7
    }

    # ---- 5. 围裙 ----
    Poly $g $P.white @(@(-9, -58), @(9, -58), @(11, -46), @(15, -20), @(-15, -20), @(-11, -46))
    Poly $g $P.whiteLo @(@(-13, -22), @(13, -22), @(12.5, -19.6), @(-12.5, -19.6))

    # ---- 6. 上衣（白衬衫）+ 藏青领结 ----
    $bl = NewPath
    $bl.AddBezier(-17, -63, -15, -54, -13, -48, -12, -43)
    $bl.AddBezier(-12, -43, -4, -41, 4, -41, 12, -43)
    $bl.AddBezier(12, -43, 13, -48, 15, -54, 17, -63)
    $bl.AddBezier(17, -63, 6, -66, -6, -66, -17, -63)
    $bl.CloseFigure()
    FillPath2 $g $P.white $bl
    $bl.Dispose()
    Ell $g $P.whiteLo -12 -52 10 14 90
    Poly $g $P.navy @(@(-9, -64), @(0, -55), @(9, -64), @(6, -66), @(0, -60), @(-6, -66))
    Ell $g $P.navy -3.4 -57 6.8 5.4

    # ---- 7. 暗红泡泡袖 + 手臂 ----
    Ell $g $P.red -25 -63 18 20
    Ell $g $P.red 7 -63 18 20
    Ell $g $P.redHi -24 -64 8 8 90
    Ell $g $P.redHi 8 -64 8 8 90
    # 前臂（伸向握把处）
    Poly $g $P.skin @(@(-16, -56), @(-6, -54), @(-5, -49), @(-15, -51))
    Poly $g $P.skin @(@(16, -54), @(6, -53), @(5, -49), @(15, -50))

    # ---- 8. 镐子（斜举向右上，斜角 -30°）----
    Draw-Pickaxe $g 1 -53 -30 54

    # ---- 9. 手（压在握把上）----
    Ell $g $P.skin -6 -55 9 9
    Ell $g $P.skinHi -5 -56 5 5
    Ell $g $P.skin 5 -53 9 9
    Ell $g $P.skinHi 6 -54 5 5

    # ---- 10. 头（脖子 → 脸 → 耳 → 前发 → 五官 → 面具）----
    Poly $g $P.skinLo @(@(-5, -70), @(5, -70), @(5, -63), @(-5, -63))
    Ell $g $P.skinHi -15 -100 30 33
    Ell $g $P.skinLo -15 -72 30 8 70
    # 耳朵
    Poly $g $P.earOut @(@(-12, -91), @(-4, -95), @(-15, -111))
    Poly $g $P.earIn @(@(-11, -93), @(-6, -95), @(-13, -106))
    Poly $g $P.earOut @(@(12, -91), @(4, -95), @(15, -111))
    Poly $g $P.earIn @(@(11, -93), @(6, -95), @(13, -106))
    # 前发（大刘海 + 侧发）
    $fr = NewPath
    $fr.AddBezier(-16, -94, -18, -84, -17, -76, -14, -70)
    $fr.AddBezier(-14, -70, -10, -78, -4, -82, 2, -83)
    $fr.AddBezier(2, -83, 8, -78, 10, -70, 12, -66)
    $fr.AddBezier(12, -66, 18, -76, 18, -86, 16, -94)
    $fr.AddBezier(16, -94, 6, -100, -6, -100, -16, -94)
    $fr.CloseFigure()
    FillPath2 $g $P.hair $fr
    $fr.Dispose()
    Poly $g $P.hairHi @(@(-13, -95), @(4, -99), @(11, -95), @(2, -93), @(-8, -93)) 150
    # 两侧长发
    Poly $g $P.hair @(@(-17, -92), @(-13, -90), @(-11, -60), @(-16, -58), @(-19, -72))
    Poly $g $P.hair @(@(17, -92), @(13, -90), @(12, -58), @(17, -56), @(20, -72))
    Poly $g $P.hairLo @(@(-17, -92), @(-14, -90), @(-13, -62), @(-16, -60), @(-18, -74))
    # 眼睛
    Ell $g $P.eyeDark -9.6 -88 8.6 9.6
    Ell $g $P.eye -8.8 -86.6 6.8 7.4
    Ell $g $P.eyeLo -8 -90 5 3 160
    Ell $g $P.white -8 -88 3 3
    Ell $g $P.eyeDark 1 -88 8.6 9.6
    Ell $g $P.eye 1.8 -86.6 6.8 7.4
    Ell $g $P.eyeLo 2.6 -90 5 3 160
    Ell $g $P.white 2.6 -88 3 3
    # 腮红 / 嘴
    Ell $g $P.blush -14 -80 9 6 110
    Ell $g $P.blush 5 -80 9 6 110
    $m = NewPath
    $m.AddBezier(-2.5, -77, -1, -75.5, 1, -75.5, 2.5, -77)
    $pen = Pn $P.eyeDark 1.1 200
    $g.DrawPath($pen, $m); $pen.Dispose(); $m.Dispose()
    # 狐狸面具（别在左侧头）
    $mk = NewPath
    $mk.AddBezier(-24, -92, -20, -98, -12, -96, -13, -88)
    $mk.AddBezier(-13, -88, -15, -82, -21, -81, -24, -85)
    $mk.CloseFigure()
    FillPath2 $g $P.white $mk
    $mk.Dispose()
    Poly $g $P.red @(@(-23, -93), @(-19, -96), @(-15, -93), @(-19, -91))
    Poly $g $P.red @(@(-22, -86), @(-17, -87), @(-19, -83))
    Poly $g $P.navyLo @(@(-22.5, -91), @(-20, -91), @(-20, -89), @(-22.5, -89))

    $g.Restore($state)
}

# 镐子：以握把中心为原点，angle 为轴向角度（度），len 为柄长
function Draw-Pickaxe($g, [double]$ox, [double]$oy, [double]$angle, [double]$len) {
    $state = $g.Save()
    $g.TranslateTransform([float]$ox, [float]$oy)
    $g.RotateTransform([float]$angle)
    $hx = $len
    # 木柄（末端略伸进镐头）
    Poly $g $P.wood @(@(-14, -2.8), @(($hx + 2), -2.8), @(($hx + 2), 2.8), @(-14, 2.8))
    Poly $g $P.woodHi @(@(-14, -2.8), @(($hx + 2), -2.8), @(($hx + 2), -1.2), @(-14, -1.2))
    Poly $g $P.woodLo @(@(-14, 1.4), @(($hx + 2), 1.4), @(($hx + 2), 2.8), @(-14, 2.8))
    # 铁镐头：垂直于柄的「弓形横梁」，两端向柄尾回弯收成尖（这才是镐，而不是单侧薄刃）
    $head = @(
        @(($hx - 7), -20), @(($hx + 3), -13), @(($hx + 7), -6), @(($hx + 8), 0),
        @(($hx + 7), 6), @(($hx + 3), 13), @(($hx - 7), 20),
        @(($hx - 2), 12), @(($hx + 2), 4), @(($hx + 2), -4), @(($hx - 2), -12)
    )
    Poly $g $P.iron $head
    # 上缘高光 / 下缘阴影（两色分层，小尺寸下也能看出立体）
    Poly $g $P.ironHi @(
        @(($hx - 5), -18), @(($hx + 2), -12), @(($hx + 6), -6), @(($hx + 7), 0),
        @(($hx + 5), 0), @(($hx + 4), -6), @(($hx + 1), -12), @(($hx - 4), -16))
    Poly $g $P.ironLo @(
        @(($hx - 5), 18), @(($hx + 2), 12), @(($hx + 6), 6), @(($hx + 7), 0),
        @(($hx + 5), 0), @(($hx + 4), 6), @(($hx + 1), 12), @(($hx - 4), 16))
    $pen = Pn $P.ironLo 1.2 220
    $g.DrawPolygon($pen, [System.Drawing.PointF[]](Pf $head)); $pen.Dispose()
    $g.Restore($state)
}

# =====================================================================
#  场景：矿井岩壁 + 矿脉 + 灯光 + 撞击效果
# =====================================================================
function Draw-RockWall($g, [int]$w, [int]$h) {
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Rectangle(0, 0, $w, $h)), (C $P.bgTop), (C $P.bgBot), 90)
    $g.FillRectangle($bg, 0, 0, $w, $h); $bg.Dispose()

    # 石砖：错缝排列，逐块微差色 + 暗缝
    $tw = [int]($w / 10.0); $th = [int]($tw * 0.62)
    for ($row = -1; $row -le [int]($h / $th) + 1; $row++) {
        for ($col = -1; $col -le [int]($w / $tw) + 1; $col++) {
            $x = $col * $tw + $(if ($row % 2 -eq 0) { 0 } else { [int]($tw / 2) })
            $y = $row * $th
            $tone = Rnd 0 3
            $col2 = if ($tone -lt 1) { $P.stone } elseif ($tone -lt 2) { $P.stoneB } else { $P.stoneC }
            Poly $g $col2 @(@(($x + 1), ($y + 1)), @(($x + $tw - 1), ($y + 1)), @(($x + $tw - 1), ($y + $th - 1)), @(($x + 1), ($y + $th - 1)))
        }
    }
    # 暗缝
    $pen = Pn $P.seam 1.4 150
    for ($row = 0; $row -le [int]($h / $th) + 1; $row++) {
        $y = $row * $th
        $g.DrawLine($pen, 0, $y, $w, $y)
    }
    $pen.Dispose()
}

function Draw-Ore($g, [double]$x, [double]$y, [double]$cell, [string]$color, [int]$n) {
    # 用小方块（而不是圆点）暗示 Minecraft 的矿石块
    for ($i = 0; $i -lt $n; $i++) {
        $ox = $x + (Rnd -1.6 1.6) * $cell
        $oy = $y + (Rnd -1.6 1.6) * $cell
        $sz = $cell * (0.66 + (Rnd 0 0.5))
        $q = $sz / 4
        Poly $g $color @(
            @(($ox - $sz / 2 + $q), ($oy - $sz / 2)), @(($ox + $sz / 2 - $q), ($oy - $sz / 2)),
            @(($ox + $sz / 2), ($oy - $sz / 2 + $q)), @(($ox + $sz / 2), ($oy + $sz / 2 - $q)),
            @(($ox + $sz / 2 - $q), ($oy + $sz / 2)), @(($ox - $sz / 2 + $q), ($oy + $sz / 2)),
            @(($ox - $sz / 2), ($oy + $sz / 2 - $q)), @(($ox - $sz / 2), ($oy - $sz / 2 + $q)))
        Ell $g 'FFFFFF' ($ox - $sz / 5) ($oy - $sz / 5) ($sz / 2.6) ($sz / 2.6) 130
    }
}

function Draw-Glow($g, [double]$x, [double]$y, [double]$r, [string]$hex, [int]$inner = 210) {
    $p = NewPath
    $p.AddEllipse([float]($x - $r), [float]($y - $r), [float]($r * 2), [float]($r * 2))
    $pg = New-Object System.Drawing.Drawing2D.PathGradientBrush($p)
    $pg.CenterColor = (C $hex $inner)
    $pg.SurroundColors = @((C $hex 0))
    $g.FillPath($pg, $p)
    $pg.Dispose(); $p.Dispose()
}

function Draw-Vignette($g, [int]$w, [int]$h) {
    $p = NewPath
    $p.AddEllipse([float](-$w * 0.25), [float](-$h * 0.3), [float]($w * 1.5), [float]($h * 1.6))
    $pg = New-Object System.Drawing.Drawing2D.PathGradientBrush($p)
    $pg.CenterColor = (C '000000' 0)
    $pg.SurroundColors = @((C '000000' 165))
    $g.FillPath($pg, $p)
    $pg.Dispose(); $p.Dispose()
}

function Draw-Impact($g, [double]$x, [double]$y) {
    # 放射状裂纹
    $pen = Pn '0E1014' 3.2 230
    for ($i = 0; $i -lt 7; $i++) {
        $a = ($i / 7.0) * 6.2832 + 0.3
        $len = 26 + (Rnd 0 34)
        $g.DrawLine($pen, [float]$x, [float]$y,
            [float]($x + [Math]::Cos($a) * $len), [float]($y + [Math]::Sin($a) * $len))
    }
    $pen.Dispose()
    Draw-Glow $g $x $y 62 'FFE6A8' 170
    # 火花 + 碎石
    for ($i = 0; $i -lt 14; $i++) {
        $a = Rnd 0 6.2832; $d = Rnd 18 90
        $sx = $x + [Math]::Cos($a) * $d; $sy = $y + [Math]::Sin($a) * $d
        $sz = Rnd 2 5
        Ell $g $(if ($i % 3 -eq 0) { 'FFF3C4' } else { 'FFC05A' }) $sx $sy $sz $sz 220
    }
    for ($i = 0; $i -lt 7; $i++) {
        $sx = $x + (Rnd -60 70); $sy = $y + (Rnd 24 96)
        $sz = Rnd 4 9
        Poly $g $P.stoneB @(@($sx, $sy), @(($sx + $sz), ($sy + $sz * 0.4)), @(($sx + $sz * 0.7), ($sy + $sz)), @(($sx - $sz * 0.2), ($sy + $sz * 0.7)))
    }
}

# =====================================================================
#  两种构图
# =====================================================================
function Render-Scene([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.InterpolationMode = 'HighQualityBicubic'

    Draw-RockWall $g $w $h

    # 矿脉：用方块感的小色块暗示"矿"
    $cell = $w / 46.0
    Draw-Ore $g ($w * 0.16) ($h * 0.30) $cell $P.diamond 7
    Draw-Ore $g ($w * 0.22) ($h * 0.52) $cell $P.gold 6
    Draw-Ore $g ($w * 0.09) ($h * 0.68) $cell $P.redstone 6
    Draw-Ore $g ($w * 0.80) ($h * 0.24) $cell $P.emerald 5
    Draw-Ore $g ($w * 0.90) ($h * 0.62) $cell $P.ironOre 6

    # 火把暖光（左）
    Draw-Glow $g ($w * 0.10) ($h * 0.30) ($w * 0.30) 'FFB45A' 120
    Draw-Glow $g ($w * 0.10) ($h * 0.30) ($w * 0.08) 'FFE7B0' 200

    # 撞击点：对准镐头前缘（与 Draw-Pickaxe 的几何对应）
    $impactX = $w * 0.583; $impactY = $h * 0.44
    Draw-Impact $g $impactX $impactY

    # 角色：脚底放在画面下方
    Draw-Jiuhu $g ($w * 0.40) ($h * 0.94) ($h / 168.0)

    # 尘埃
    for ($i = 0; $i -lt 46; $i++) {
        $dx = Rnd 0 $w; $dy = Rnd 0 $h; $ds = Rnd 1.2 3.4
        Ell $g 'D9C79A' $dx $dy $ds $ds (Rnd 20 75)
    }

    Draw-Vignette $g $w $h
    $g.Dispose()
    return $bmp
}

function Render-Portrait([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'

    Draw-RockWall $g $size $size
    $cell = $size / 15.0
    Draw-Ore $g ($size * 0.16) ($size * 0.22) $cell $P.diamond 4
    Draw-Ore $g ($size * 0.86) ($size * 0.74) $cell $P.gold 4
    Draw-Glow $g ($size * 0.5) ($size * 0.44) ($size * 0.72) '5A3A18' 120

    # 只画上半身：头部落在画面上方 44%，镐头在右上，裙摆贴近下边缘
    $s = $size / 118.0
    Draw-Jiuhu $g ($size * 0.34) ($size * 0.44 + 84 * $s) $s

    for ($i = 0; $i -lt 18; $i++) {
        $dx = Rnd 0 $size; $dy = Rnd 0 $size; $ds = Rnd 1.0 2.6
        Ell $g 'D9C79A' $dx $dy $ds $ds (Rnd 20 60)
    }
    Draw-Vignette $g $size $size
    $g.Dispose()
    return $bmp
}

function Save-Scaled($src, [string]$path, [int]$tw, [int]$th) {
    $dst = New-Object System.Drawing.Bitmap($tw, $th)
    $g = [System.Drawing.Graphics]::FromImage($dst)
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.PixelOffsetMode = 'HighQuality'
    $g.CompositingQuality = 'HighQuality'
    $g.DrawImage($src, 0, 0, $tw, $th)
    $g.Dispose()
    $dst.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $dst.Dispose()
}

# ---------------- 输出 ----------------
$ss = 4   # 超采样倍数
$scene = Render-Scene (1280 * $ss / 3) (720 * $ss / 3)
Save-Scaled $scene (Join-Path $OutDir 'cover.png') 240 150
Save-Scaled $scene (Join-Path $OutDir 'publish\banner-1280x720.png') 1280 720
$scene.Dispose()

$portrait = Render-Portrait (512 * $ss / 4)
Save-Scaled $portrait (Join-Path $OutDir 'src\main\resources\logo.png') 128 128
Save-Scaled $portrait (Join-Path $OutDir 'publish\icon-512x512.png') 512 512
$portrait.Dispose()

Write-Host "封面已生成："
Write-Host "  cover.png                        240x150"
Write-Host "  src/main/resources/logo.png      128x128"
Write-Host "  publish/banner-1280x720.png      1280x720"
Write-Host "  publish/icon-512x512.png         512x512"

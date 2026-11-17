package com.github.toober77.ytpostimage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EditMathTest {
    private val W = 1536.0
    private val H = 2048.0

    private fun near(a: Double, b: Double, eps: Double = 1e-6) = assertTrue("$a vs $b", abs(a - b) <= eps)

    // ---------- 比例解析 ----------
    @Test fun parseRatioForms() {
        near(9.0 / 16, EditMath.parseRatio("9:16")!!)
        near(9.0 / 16, EditMath.parseRatio(" 9 ： 16 ")!!)
        near(2.0 / 3, EditMath.parseRatio("2/3")!!)
        near(16.0 / 9, EditMath.parseRatio("16x9")!!)
        near(1.0, EditMath.parseRatio("1:1")!!)
        near(0.5625, EditMath.parseRatio("0.5625")!!)
        near(1.5, EditMath.parseRatio("3:2")!!)
    }

    @Test fun parseRatioInvalid() {
        assertNull(EditMath.parseRatio(null))
        assertNull(EditMath.parseRatio(""))
        assertNull(EditMath.parseRatio("abc"))
        assertNull(EditMath.parseRatio("9:"))
        assertNull(EditMath.parseRatio("9:0"))
        assertNull(EditMath.parseRatio("0:5"))
        assertNull(EditMath.parseRatio("1:2:3"))
        assertNull(EditMath.parseRatio("1000:1"))
    }

    // ---------- 畫布大小 ----------
    @Test fun frameSizes() {
        val (a, b) = EditMath.frameSize(W, H, 0.0); near(W, a); near(H, b)
        val (c, d) = EditMath.frameSize(W, H, 90.0); near(H, c); near(W, d)
        val (e, f) = EditMath.frameSize(W, H, -90.0); near(H, e); near(W, f)
        val (g, h) = EditMath.frameSize(100.0, 100.0, 45.0); near(141.421356, g, 1e-4); near(141.421356, h, 1e-4)
    }

    // ---------- 圖內判斷 ----------
    @Test fun insideImageNoRotation() {
        assertTrue(EditMath.insideImage(CropRect(0.0, 0.0, W, H), W, H, 0.0))
        assertFalse(EditMath.insideImage(CropRect(-5.0, 0.0, W, H), W, H, 0.0))
        assertFalse(EditMath.insideImage(CropRect(0.0, 0.0, W + 5, H), W, H, 0.0))
    }

    @Test fun insideImageRotated45() {
        // 100x100 旋轉 45°：畫布約 141.4，四個角落是空的
        assertFalse(EditMath.insideImage(CropRect(0.0, 0.0, 141.4, 141.4), 100.0, 100.0, 45.0))
        assertTrue(EditMath.insideImage(CropRect.centered(70.7, 70.7, 70.0, 70.0), 100.0, 100.0, 45.0))
        assertFalse(EditMath.insideImage(CropRect.centered(70.7, 70.7, 72.0, 72.0), 100.0, 100.0, 45.0))
    }

    @Test fun insideImageSameForAnyQuarterTurn() {
        for (q in 0..3) {
            val (bw, bh) = EditMath.frameSize(W, H, q * 90.0)
            assertTrue(EditMath.insideImage(CropRect(0.0, 0.0, bw, bh), W, H, q * 90.0))
            assertFalse(EditMath.insideImage(CropRect(-1.0, 0.0, bw, bh), W, H, q * 90.0))
        }
    }

    // ---------- 最大置中裁切框 ----------
    @Test fun largestCenteredFree() {
        val r = EditMath.largestCentered(W, H, 0.0, null)
        near(0.0, r.left, 1e-2); near(W, r.right, 1e-2); near(H, r.bottom, 1e-2)
    }

    @Test fun largestCentered916Dimensions() {
        val r = EditMath.largestCentered(W, H, 0.0, 9.0 / 16)
        near(1152.0, r.width, 1e-2); near(2048.0, r.height, 1e-2)
        near(W / 2, r.cx, 1e-2); near(H / 2, r.cy, 1e-2)
    }

    @Test fun largestCentered23() {
        val r = EditMath.largestCentered(W, H, 0.0, 2.0 / 3)
        near(2.0 / 3, r.width / r.height, 1e-6)
        near(1365.333, r.width, 1e-2); near(2048.0, r.height, 1e-2)
    }

    @Test fun largestCenteredRotatedStaysInside() {
        for (deg in listOf(5.0, 17.3, -30.0, 44.9, 90.0, 93.5, 180.0, -135.0)) {
            for (asp in listOf<Double?>(null, 9.0 / 16, 2.0 / 3, 1.0, 16.0 / 9)) {
                val r = EditMath.largestCentered(W, H, deg, asp)
                assertTrue("deg=$deg asp=$asp $r", EditMath.insideImage(r, W, H, deg))
                // 再放大一點就會超出（最大性）
                val bigger = CropRect.centered(r.cx, r.cy, r.width * 1.01, r.height * 1.01)
                assertFalse("deg=$deg asp=$asp", EditMath.insideImage(bigger, W, H, deg))
            }
        }
    }

    // ---------- clamp / fit ----------
    @Test fun clampStepStopsAtBoundary() {
        val start = CropRect(100.0, 100.0, 500.0, 500.0)
        val r = EditMath.clampStep(start, start.offset(5000.0, 0.0)) { EditMath.insideImage(it, W, H, 0.0) }
        near(W, r.right, 1e-2); near(400.0, r.width, 1e-2)
    }

    @Test fun clampStepValidPassesThrough() {
        val start = CropRect(100.0, 100.0, 500.0, 500.0)
        val p = start.offset(10.0, 10.0)
        assertEquals(p, EditMath.clampStep(start, p) { true })
    }

    @Test fun fitInsideShrinksKeepingAspect() {
        val r = CropRect(0.0, 0.0, W, H)
        val f = EditMath.fitInside(r, W, H, 10.0)
        assertTrue(EditMath.insideImage(f, W, H, 10.0))
        near(W / H, f.width / f.height, 1e-2)
        assertTrue(f.width < W)
    }

    @Test fun fitInsideNoopWhenValid() {
        val r = CropRect(100.0, 100.0, 300.0, 300.0)
        assertEquals(r, EditMath.fitInside(r, W, H, 0.0))
    }

    // ---------- resize ----------
    private val base = CropRect(200.0, 300.0, 800.0, 1100.0)

    @Test fun resizeMove() = assertEquals(base.offset(10.0, -20.0), EditMath.resize(base, Handle.MOVE, 10.0, -20.0, null, 10.0))

    @Test fun resizeFreeCorner() {
        val r = EditMath.resize(base, Handle.BR, 50.0, 60.0, null, 10.0)
        assertEquals(CropRect(200.0, 300.0, 850.0, 1160.0), r)
        val t = EditMath.resize(base, Handle.TL, 50.0, 60.0, null, 10.0)
        assertEquals(CropRect(250.0, 360.0, 800.0, 1100.0), t)
    }

    @Test fun resizeFreeEdge() {
        assertEquals(CropRect(150.0, 300.0, 800.0, 1100.0), EditMath.resize(base, Handle.L, -50.0, 99.0, null, 10.0))
        assertEquals(CropRect(200.0, 300.0, 800.0, 1000.0), EditMath.resize(base, Handle.B, 99.0, -100.0, null, 10.0))
    }

    @Test fun resizeFreeMinSize() {
        val r = EditMath.resize(base, Handle.R, -5000.0, 0.0, null, 40.0)
        near(40.0, r.width); near(200.0, r.left)
        val t = EditMath.resize(base, Handle.T, 0.0, 5000.0, null, 40.0)
        near(40.0, t.height); near(1100.0, t.bottom)
    }

    @Test fun resizeLockedKeepsAspectAndAnchor() {
        val a = 9.0 / 16
        val start = CropRect.centered(500.0, 500.0, 450.0, 800.0)
        for (h in listOf(Handle.TL, Handle.TR, Handle.BL, Handle.BR)) {
            for ((dx, dy) in listOf(30.0 to 10.0, -40.0 to -90.0, 100.0 to -20.0)) {
                val r = EditMath.resize(start, h, dx, dy, a, 20.0)
                near(a, r.width / r.height, 1e-9)
                // 對角錨點不動
                when (h) {
                    Handle.TL -> { near(start.right, r.right); near(start.bottom, r.bottom) }
                    Handle.TR -> { near(start.left, r.left); near(start.bottom, r.bottom) }
                    Handle.BL -> { near(start.right, r.right); near(start.top, r.top) }
                    else -> { near(start.left, r.left); near(start.top, r.top) }
                }
            }
        }
    }

    @Test fun resizeLockedEdgesKeepAspect() {
        val a = 2.0 / 3
        val start = CropRect.centered(500.0, 500.0, 400.0, 600.0)
        for (h in listOf(Handle.L, Handle.R, Handle.T, Handle.B)) {
            val r = EditMath.resize(start, h, 37.0, -53.0, a, 20.0)
            near(a, r.width / r.height, 1e-9)
        }
    }

    @Test fun resizeLockedMinSize() {
        val a = 9.0 / 16
        val r = EditMath.resize(CropRect(0.0, 0.0, 450.0, 800.0), Handle.BR, -5000.0, -5000.0, a, 50.0)
        assertTrue(r.width >= 50.0 - 1e-9 && r.height >= 50.0 - 1e-9)
        near(a, r.width / r.height, 1e-9)
    }

    // ---------- 90° 旋轉 ----------
    @Test fun rotateRectCwCcwRoundTrip() {
        val r = CropRect(100.0, 250.0, 700.0, 900.0)
        val cw = EditMath.rotateRectCw(r, H)           // 轉前畫布 W×H → 轉後 H×W
        val back = EditMath.rotateRectCcw(cw, H)       // 轉後畫布寬 = H
        assertEquals(r, back)
    }

    @Test fun rotateRectCwCornerMapping() {
        // 左上角的裁切框轉順時針後跑到右上角
        val r = CropRect(0.0, 0.0, 100.0, 200.0)
        val cw = EditMath.rotateRectCw(r, H)
        assertEquals(CropRect(H - 200.0, 0.0, H, 100.0), cw)
    }

    @Test fun fourQuarterTurnsIsIdentity() {
        var r = CropRect(100.0, 250.0, 700.0, 900.0)
        var fw = W; var fh = H
        repeat(4) { r = EditMath.rotateRectCw(r, fh); val t = fw; fw = fh; fh = t }
        assertEquals(CropRect(100.0, 250.0, 700.0, 900.0), r)
    }

    @Test fun rotatedRectStillInsideAfterQuarterTurn() {
        val r = CropRect(100.0, 250.0, 700.0, 900.0)
        assertTrue(EditMath.insideImage(r, W, H, 0.0))
        assertTrue(EditMath.insideImage(EditMath.rotateRectCw(r, H), W, H, 90.0))
        assertTrue(EditMath.insideImage(EditMath.rotateRectCcw(r, W), W, H, -90.0))
    }

    // ---------- 狀態 ----------
    private fun st(q: Int = 0, fine: Double = 0.0, crop: CropRect = CropRect(0.0, 0.0, W, H)) =
        EditState(W.toInt(), H.toInt(), q, fine, crop)

    @Test fun identityDetection() {
        assertTrue(EditMath.isIdentity(st()))
        assertTrue(EditMath.isIdentity(st(crop = CropRect(0.2, 0.1, W - 0.3, H))))
        assertFalse(EditMath.isIdentity(st(q = 1, crop = CropRect(0.0, 0.0, H, W))))
        assertFalse(EditMath.isIdentity(st(fine = 1.0)))
        assertFalse(EditMath.isIdentity(st(crop = CropRect(10.0, 0.0, W, H))))
        assertTrue(EditMath.isIdentity(st(q = 4)))
    }

    @Test fun totalAngle() {
        near(93.5, st(q = 1, fine = 3.5).angle)
        near(-90.0 - 0.0, st(q = -1).angle)
        near(270.0, st(q = 3).angle)
    }

    @Test fun outputSizeRoundsAndIsPositive() {
        assertEquals(1152 to 2048, EditMath.outputSize(CropRect(0.0, 0.0, 1151.6, 2048.2)))
        assertEquals(1 to 1, EditMath.outputSize(CropRect(0.0, 0.0, 0.1, 0.0)))
    }

    @Test fun outputFormat() {
        assertTrue(EditMath.outputIsPng("png"))
        assertTrue(EditMath.outputIsPng("gif"))
        assertFalse(EditMath.outputIsPng("jpg"))
        assertFalse(EditMath.outputIsPng("webp"))
    }

    @Test fun clampFine() {
        near(45.0, EditMath.clampFine(80.0)); near(-45.0, EditMath.clampFine(-80.0)); near(3.2, EditMath.clampFine(3.2))
        assertNotNull(EditMath.parseRatio("9:16"))
    }
}

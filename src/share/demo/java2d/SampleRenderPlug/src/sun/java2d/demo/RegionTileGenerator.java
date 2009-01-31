/*
 * Copyright 2007 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package sun.java2d.demo;

import java.awt.Shape;
import java.awt.BasicStroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;

import sun.java2d.pipe.Region;
import sun.java2d.pipe.RegionIterator;
import sun.java2d.pipe.AATileGenerator;
import sun.java2d.pipe.ShapeSpanIterator;

/**
 * This class is used to convert raw geometry into 8-bit alpha tiles
 * using oversampled rendering of the shape into a Region object.
 * The Region object is then sampled a tile at a time per the contract
 * of the AATileGenerator interface.
 * The quality is configurable by changing the setting for OVERSAMPLING.
 * The default setting supplied here of 16 is a reasonable match for
 * the subsampling quality of the closed source Ductus renderer, but
 * does not perform nearly as well.  Lower values could be used to
 * improve performance at the cost of quality.
 */
public class RegionTileGenerator
    implements AATileGenerator
{
    public static final int OVERSAMPLING = 16;
    public static final int TILE_SIZE = 32;

    Region region;
    int lox, loy, hix, hiy;
    int curx, cury;

    public RegionTileGenerator(RegionRenderingEngine rre,
                               Shape s,
                               AffineTransform at,
                               Region clip,
                               BasicStroke bs,
                               boolean thin,
                               boolean normalize,
                               int bbox[])
    {
        AffineTransform ataa =
            AffineTransform.getScaleInstance(OVERSAMPLING, OVERSAMPLING);
        ataa.concatenate(at);
        ShapeSpanIterator sr = new ShapeSpanIterator(false);
        try {
            sr.setOutputAreaXYXY(clip.getLoX() * OVERSAMPLING,
                                 clip.getLoY() * OVERSAMPLING,
                                 clip.getHiX() * OVERSAMPLING,
                                 clip.getHiY() * OVERSAMPLING);
            if (bs == null) {
                sr.appendPath(s.getPathIterator(ataa));
            } else {
                sr.setRule(PathIterator.WIND_NON_ZERO);
                rre.strokeTo(s, ataa, bs, thin, normalize, true, sr);
            }
            sr.getPathBox(bbox);
            region = Region.getInstance(bbox);
            region.appendSpans(sr);
        } finally {
            sr.dispose();
        }

        if (region.isEmpty()) {
            lox = loy = hix = hiy = 0;
        } else {
            region.getBounds(bbox);
            lox = bbox[0] / OVERSAMPLING;
            loy = bbox[1] / OVERSAMPLING;
            hix = (bbox[2] + OVERSAMPLING-1) / OVERSAMPLING;
            hiy = (bbox[3] + OVERSAMPLING-1) / OVERSAMPLING;
        }
        bbox[0] = lox;
        bbox[1] = loy;
        bbox[2] = hix;
        bbox[3] = hiy;
        curx = lox;
        cury = loy;
    }

    @Override
    public int getTileWidth() {
        return TILE_SIZE;
    }

    @Override
    public int getTileHeight() {
        return TILE_SIZE;
    }

    @Override
    public int getTypicalAlpha() {
        return 0x80;
    }

    @Override
    public void nextTile() {
        if ((curx += TILE_SIZE) >= hix) {
            cury += TILE_SIZE;
            curx = lox;
        }
    }

    static byte countToAlpha[];

    static {
        countToAlpha = new byte[OVERSAMPLING * OVERSAMPLING + 1];
        for (int i = 0; i < countToAlpha.length; i++) {
            countToAlpha[i] = (byte) (i * 255 / (OVERSAMPLING * OVERSAMPLING));
        }
    }

    @Override
    public void getAlpha(byte tile[], int offset, int rowstride) {
        int atmp[] = new int[TILE_SIZE * TILE_SIZE];
        int dx0 = curx;
        int dy0 = cury;
        int dx1 = Math.min(hix, dx0 + TILE_SIZE);
        int dy1 = Math.min(hiy, dy0 + TILE_SIZE);
        Region r = this.region;

        RegionIterator rowiter = r.getIterator();
        int spanx0 = dx0 * OVERSAMPLING;
        int spany0 = dy0 * OVERSAMPLING;
        int spanx1 = dx1 * OVERSAMPLING;
        int spany1 = dy1 * OVERSAMPLING;
        int span[] = { spanx0, spany0, spanx0, spany0 };
        if (r.isRectangular()) {
            r.getBounds(span);
            if (span[0] < spanx0) span[0] = spanx0;
            if (span[1] < spany0) span[1] = spany0;
            if (span[2] > spanx1) span[2] = spanx1;
            if (span[3] > spany1) span[3] = spany1;
        }
        java.util.Arrays.fill(atmp, 0);
        while (true) {
            if (span[1] >= span[3]) {
                if (!rowiter.nextYRange(span) || span[1] >= spany1) break;
                if (span[1] < spany0) span[1] = spany0;
                if (span[3] > spany1) span[3] = spany1;
                span[0] = span[2] = spanx0;
                continue;
            }
            if (span[0] >= span[2]) {
                if (!rowiter.nextXBand(span) || span[0] >= spanx1) {
                    span[1] = span[3];
                    continue;
                }
                if (span[0] < spanx0) span[0] = spanx0;
                if (span[2] > spanx1) span[2] = spanx1;
                continue;
            }
            int y = span[1];
            while (y < span[3]) {
                int row = (y / OVERSAMPLING);
                int dy = (row+1) * OVERSAMPLING;
                if (dy > span[3]) dy = span[3];
                dy -= y;
                row = offset + (row - dy0) * rowstride;
                int x = span[0];
                int col = (x / OVERSAMPLING);
                int dx = (col+1) * OVERSAMPLING;
                if (dx > span[2]) dx = span[2];
                dx -= x;
                col = col - dx0;
                atmp[row + col] += dx * dy;
                x += dx;
                col++;
                while (x + OVERSAMPLING <= span[2]) {
                    atmp[row + col] += dy * OVERSAMPLING;
                    x += OVERSAMPLING;
                    col++;
                }
                if (x < span[2]) {
                    atmp[row + col] += (span[2]-x) * dy;
                }
                y += dy;
            }
            span[0] = span[2];
        }

        for (int i = 0; i < atmp.length; i++) {
            tile[i] = countToAlpha[atmp[i]];
        }
        nextTile();
    }

    @Override
    public void dispose() {}
}

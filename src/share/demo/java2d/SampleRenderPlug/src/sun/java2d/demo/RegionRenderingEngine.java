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

import sun.java2d.pipe.RenderingEngine;
import sun.java2d.pipe.AATileGenerator;
import sun.java2d.pipe.Region;
import sun.awt.geom.PathConsumer2D;
import sun.dc.DuctusRenderingEngine;

/**
 * This class implements the RenderingEngine API and constructs a
 * RegionTileGenerator to generate AA tiles for antialiasing operations.
 * This class does not implement the createStrokedShape or strokeTo
 * methods of the RenderingEngine class, but rather delegates those
 * operations to an instance of DuctusRenderingEngine.
 * A quick and dirty line widening algorithm could replace those
 * usages to completely wean this class off of the Ductus code, but
 * this class mainly serves as an example of how to get an alternate
 * implementation of RenderingEngine built and used by the OpenJDK.
 */
public class RegionRenderingEngine extends RenderingEngine {
    RenderingEngine stub = new DuctusRenderingEngine();

    @Override
    public Shape createStrokedShape(Shape src,
                                    float width,
                                    int caps,
                                    int join,
                                    float miterlimit,
                                    float dashes[],
                                    float dashphase)
    {
        return stub.createStrokedShape(src, width, caps, join, miterlimit,
                                       dashes, dashphase);
    }

    @Override
    public void strokeTo(Shape src,
                         AffineTransform at,
                         BasicStroke bs,
                         boolean thin,
                         boolean normalize,
                         boolean antialias,
                         PathConsumer2D consumer)
    {
        stub.strokeTo(src, at, bs, thin, normalize, antialias, consumer);
    }

    public AATileGenerator getAATileGenerator(Shape s,
                                              AffineTransform at,
                                              Region clip,
                                              BasicStroke bs,
                                              boolean thin,
                                              boolean normalize,
                                              int bbox[])
    {
        return new RegionTileGenerator(this, s, at, clip, bs,
                                       thin, normalize, bbox);
    }

    public float getMinimumAAPenSize() {
        return 1.0f / RegionTileGenerator.OVERSAMPLING;
    }
}

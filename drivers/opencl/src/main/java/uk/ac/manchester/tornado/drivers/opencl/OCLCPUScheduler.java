/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornado
 *
 * Copyright (c) 2013-2018, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.drivers.opencl;

import static uk.ac.manchester.tornado.runtime.common.Tornado.getProperty;

import uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData;

public class OCLCPUScheduler extends OCLKernelScheduler {

    private final double CPU_COMPUTE_UNIT_COEFF = Double.parseDouble(getProperty("tornado.opencl.cpu.coeff", "1.0"));

    public OCLCPUScheduler(final OCLDeviceContext context) {
        super(context);
    }

    @Override
    public void calculateGlobalWork(final TaskMetaData meta) {
        long[] maxItems = deviceContext.getDevice().getMaxWorkItemSizes();

        final long[] globalWork = meta.getGlobalWork();
        for (int i = 0; i < meta.getDims(); i++) {
            if (meta.enableThreadCoarsener()) {
                globalWork[i] = maxItems[i] > 1 ? (long) (meta.getDomain().get(i).cardinality()) : 1;
            } else {
                globalWork[i] = i == 0 ? (long) (deviceContext.getDevice().getMaxComputeUnits() * CPU_COMPUTE_UNIT_COEFF) : 1;
            }
        }
    }

    @Override
    public void calculateLocalWork(final TaskMetaData meta) {
        final long[] localWork = meta.getLocalWork();
        meta.setLocalWorkToNull();
    }

}
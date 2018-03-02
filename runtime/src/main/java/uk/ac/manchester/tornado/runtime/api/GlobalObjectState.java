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
package uk.ac.manchester.tornado.runtime.api;

import java.util.HashMap;
import java.util.Map;

import uk.ac.manchester.tornado.common.DeviceObjectState;
import uk.ac.manchester.tornado.common.TornadoDevice;

public class GlobalObjectState {

    private boolean shared;
    private boolean exclusive;

    private TornadoDevice owner;

    private final Map<TornadoDevice, DeviceObjectState> deviceStates;

    public GlobalObjectState() {
        shared = false;
        exclusive = false;
        owner = null;
        deviceStates = new HashMap<>();
    }

    public boolean isShared() {
        return shared;
    }

    public boolean isExclusive() {
        return exclusive;
    }

    public TornadoDevice getOwner() {
        return owner;
    }

    public DeviceObjectState getDeviceState() {
        return getDeviceState(getOwner());
    }

    public DeviceObjectState getDeviceState(TornadoDevice device) {
        if (!deviceStates.containsKey(device)) {
            deviceStates.put(device, new DeviceObjectState());
        }
        return deviceStates.get(device);
    }

    public void setOwner(TornadoDevice device) {
        owner = device;
        if (!deviceStates.containsKey(owner)) {
            deviceStates.put(device, new DeviceObjectState());
        }
    }

    public void invalidate() {
        for (TornadoDevice device : deviceStates.keySet()) {
            final DeviceObjectState deviceState = deviceStates.get(device);
            deviceState.invalidate();
        }
    }

    public void clear() {
        deviceStates.clear();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append((isExclusive()) ? "X" : "-");
        sb.append((isShared()) ? "S" : "-");
        sb.append(" ");

        if (owner != null) {
            sb.append("owner=").append(owner.toString()).append(", devices=[");
        }

        for (TornadoDevice device : deviceStates.keySet()) {
            if (device != owner) {
                sb.append(device.toString()).append(" ");
            }
        }

        sb.append("]");

        return sb.toString();
    }

}
/*
 * Copyright (C) 2018 LEIDOS.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package gov.dot.fhwa.saxton.carma.interfacemgr;


import java.util.ArrayList;
import java.util.List;

public class DriverInfo {

    private String          name;
    private DriverState     state;
    private List<String>    capabilities;
    //the different possible categories a driver can represent. A given driver may cover multiple
    // categories, so we can't use an enum here.
    private boolean         can;
    private boolean         sensor;
    private boolean         position;
    private boolean         comms;
    private boolean         lon_controller;
    private boolean         lat_controller;

    public DriverInfo() {
        name = "";
        state = DriverState.OFF;
        can = false;
        sensor = false;
        position = false;
        comms = false;
        lon_controller = false;
        lat_controller = false;
        capabilities = new ArrayList<String>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DriverState getState() {
        return state;
    }

    public void setState(DriverState state) {
        this.state = state;
    }

    public boolean isCan() {
        return can;
    }

    public void setCan(boolean can) {
        this.can = can;
    }

    public boolean isSensor() {
        return sensor;
    }

    public void setSensor(boolean sensor) {
        this.sensor = sensor;
    }

    public boolean isPosition() {
        return position;
    }

    public void setPosition(boolean position) {
        this.position = position;
    }

    public boolean isComms() {
        return comms;
    }

    public void setComms(boolean comms) {
        this.comms = comms;
    }

    public boolean isLonController() {
        return lon_controller;
    }

    public boolean isLatController() {
        return lat_controller;
    }
    
    public void setLonController(boolean lonController) {
        this.lon_controller = lonController;
    }

    public void setLatController(boolean latController) {
        this.lat_controller = latController;
    }
    
    public List<String> getCapabilities() { return capabilities; }

    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }

    public boolean equalCategoryAndState(DriverInfo b) {
        if (!name.equals(b.getName())) {
            return false;
        }
        if (state != b.getState()) {
            return false;
        }
        if (can != b.isCan()) {
            return false;
        }
        if (comms != b.isComms()) {
            return false;
        }
        if (lon_controller != b.isLonController()) {
            return false;
        }
        if (lat_controller != b.isLatController()) {
            return false;
        }
        if (position != b.isPosition()) {
            return false;
        }
        if (sensor != b.isSensor()) {
            return false;
        }

        return true;
    }

    /**
     * Determines if the given category is provided by the driver.
     *
     * @param cat - category in question (may be UNDEFINED, in which case a driver always matches)
     * @return true if the driver does fall into the given category
     */
    protected boolean hasCategory(DriverCategory cat) {

        if (cat == DriverCategory.UNDEFINED) {
            return true;
        }

        if ((cat == DriverCategory.LON_CONTROLLER   &&  lon_controller)     ||
                (cat == DriverCategory.LAT_CONTROLLER   &&  lat_controller) ||
                (cat == DriverCategory.COMMS    &&  comms)        ||
                (cat == DriverCategory.CAN      &&  can)          ||
                (cat == DriverCategory.POSITION &&  position)     ||
                (cat == DriverCategory.SENSOR   &&  sensor)) {
            return true;
        }

        return false;
    }
}

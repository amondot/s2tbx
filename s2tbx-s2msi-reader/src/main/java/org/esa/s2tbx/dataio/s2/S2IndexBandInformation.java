/*
 * Copyright (C) 2014-2015 CS-SI (foss-contact@thor.si.c-s.fr)
 * Copyright (C) 2013-2015 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.s2tbx.dataio.s2;

import org.esa.snap.core.datamodel.IndexCoding;

/**
 * @author J. Malik
 */
public class S2IndexBandInformation extends S2BandInformation {

    private IndexCoding indexCoding;

    public S2IndexBandInformation(String physicalBand,
                                  S2SpatialResolution resolution,
                                  String imageFileTemplate,
                                  String description,
                                  String unit,
                                  IndexCoding indexCoding) {
        super(physicalBand, resolution, imageFileTemplate, description, unit);
        this.indexCoding = indexCoding;
    }

    public IndexCoding getIndexCoding() {
        return this.indexCoding;
    }
}

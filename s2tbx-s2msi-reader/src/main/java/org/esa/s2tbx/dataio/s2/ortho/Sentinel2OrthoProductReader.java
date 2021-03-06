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

package org.esa.s2tbx.dataio.s2.ortho;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.glevel.MultiLevelImage;
import com.bc.ceres.glevel.support.AbstractMultiLevelSource;
import com.bc.ceres.glevel.support.DefaultMultiLevelImage;
import com.bc.ceres.glevel.support.DefaultMultiLevelModel;
import com.bc.ceres.glevel.support.DefaultMultiLevelSource;
import com.vividsolutions.jts.geom.Polygon;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.esa.s2tbx.dataio.jp2.TileLayout;
import org.esa.s2tbx.dataio.jp2.internal.JP2TileOpImage;
import org.esa.s2tbx.dataio.openjpeg.StackTraceUtils;
import org.esa.s2tbx.dataio.s2.*;
import org.esa.s2tbx.dataio.s2.filepatterns.S2ProductFilename;
import org.esa.s2tbx.dataio.s2.gml.EopPolygon;
import org.esa.s2tbx.dataio.s2.gml.GmlFilter;
import org.esa.s2tbx.dataio.s2.masks.MaskInfo;
import org.esa.s2tbx.dataio.s2.ortho.filepatterns.S2OrthoGranuleDirFilename;
import org.esa.s2tbx.dataio.s2.ortho.filepatterns.S2OrthoGranuleMetadataFilename;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.image.ImageManager;
import org.esa.snap.core.image.SourceImageScaler;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.io.FileUtils;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureImpl;
import org.geotools.filter.identity.FeatureIdImpl;
import org.geotools.referencing.CRS;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import javax.media.jai.*;
import javax.media.jai.operator.ConstantDescriptor;
import javax.media.jai.operator.CropDescriptor;
import javax.media.jai.operator.MosaicDescriptor;
import javax.media.jai.operator.TranslateDescriptor;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;


/**
 * <p>
 * Base class for Sentinel-2 readers of orthorectified products
 * </p>
 * <p>
 * To read single tiles, select any tile image file (IMG_*.jp2) within a product package. The reader will then
 * collect other band images for the selected tile and will also try to read the metadata file (MTD_*.xml).
 * </p>
 * <p>To read an entire scene, select the metadata file (MTD_*.xml) within a product package. The reader will then
 * collect other tile/band images and create a mosaic on the fly.
 * </p>
 *
 * @author Norman Fomferra
 * @author Nicolas Ducoin
 */
public abstract class Sentinel2OrthoProductReader extends Sentinel2ProductReader {

    static final int SUN_ZENITH_GRID_INDEX = 0;
    static final int SUN_AZIMUTH_GRID_INDEX = 1;
    static final int VIEW_ZENITH_GRID_INDEX = 2;
    static final int VIEW_AZIMUTH_GRID_INDEX = 3;

    private final String epsgCode;
    protected final Logger logger;

    public Sentinel2OrthoProductReader(ProductReaderPlugIn readerPlugIn, ProductInterpretation interpretation, String epsgCode) {
        super(readerPlugIn, interpretation);
        logger = SystemUtils.LOG;
        this.epsgCode = epsgCode;
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight, int sourceStepX, int sourceStepY, Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm) throws IOException {
        // Should never not come here, since we have an OpImage that reads data
    }

    protected abstract String getReaderCacheDir();

    protected abstract S2Metadata parseHeader(File file, String granuleName, S2Config config, String epsg) throws IOException;

    protected abstract String getImagePathString(String imageFileName, S2SpatialResolution resolution);

    @Override
    protected Product getMosaicProduct(File metadataFile) throws IOException {
        Objects.requireNonNull(metadataFile);

        boolean isAGranule = S2OrthoGranuleMetadataFilename.isGranuleFilename(metadataFile.getName());

        if (isAGranule) {
            logger.fine("Reading a granule");
        }

        TimeProbe timeProbe = TimeProbe.start();
        // update the tile layout
        updateTileLayout(metadataFile.toPath(), isAGranule);
        SystemUtils.LOG.fine(String.format("[timeprobe] updateTileLayout : %s ms", timeProbe.elapsed(TimeUnit.MILLISECONDS)));

        String filterTileId = null;
        File rootMetaDataFile = null;
        String granuleDirName = null;

        timeProbe.reset();
        // we need to recover parent metadata file if we have a granule
        if (isAGranule) {
            granuleDirName = metadataFile.getParentFile().getName();
            try {
                Objects.requireNonNull(metadataFile.getParentFile());
                Objects.requireNonNull(metadataFile.getParentFile().getParentFile());
                Objects.requireNonNull(metadataFile.getParentFile().getParentFile().getParentFile());
            } catch (NullPointerException npe) {
                throw new IOException(String.format("Unable to retrieve the product associated to granule metadata file [%s]", metadataFile.getName()));
            }

            File up2levels = metadataFile.getParentFile().getParentFile().getParentFile();
            File tileIdFilter = metadataFile.getParentFile();

            filterTileId = tileIdFilter.getName();

            File[] files = up2levels.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (S2ProductFilename.isProductFilename(f.getName()) && S2ProductFilename.isMetadataFilename(f.getName())) {
                        rootMetaDataFile = f;
                        break;
                    }
                }
            }
            if (rootMetaDataFile == null) {
                throw new IOException(String.format("Unable to retrieve the product associated to granule metadata file [%s]", metadataFile.getName()));
            }
        } else {
            rootMetaDataFile = metadataFile;
        }

        final String aFilter = filterTileId;

        S2Metadata metadataHeader = parseHeader(rootMetaDataFile, granuleDirName, getConfig(), epsgCode);
        SystemUtils.LOG.fine(String.format("[timeprobe] metadata parsing : %s ms", timeProbe.elapsed(TimeUnit.MILLISECONDS)));
        timeProbe.reset();

        S2OrthoSceneLayout sceneDescription = S2OrthoSceneLayout.create(metadataHeader);
        logger.fine("Scene Description: " + sceneDescription);

        File productDir = getProductDir(rootMetaDataFile);
        initCacheDir(productDir);

        S2Metadata.ProductCharacteristics productCharacteristics = metadataHeader.getProductCharacteristics();

        Product product = new Product(FileUtils.getFilenameWithoutExtension(rootMetaDataFile),
                                      "S2_MSI_" + productCharacteristics.getProcessingLevel(),
                                      sceneDescription.getSceneDimension(getProductResolution()).width,
                                      sceneDescription.getSceneDimension(getProductResolution()).height);

        for (MetadataElement metadataElement : metadataHeader.getMetadataElements()) {
            product.getMetadataRoot().addElement(metadataElement);
        }
        product.setFileLocation(metadataFile);

        try {
            product.setSceneGeoCoding(new CrsGeoCoding(CRS.decode(this.epsgCode),
                                                       product.getSceneRasterWidth(),
                                                       product.getSceneRasterHeight(),
                                                       sceneDescription.getSceneOrigin()[0],
                                                       sceneDescription.getSceneOrigin()[1],
                                                       this.getProductResolution().resolution,
                                                       this.getProductResolution().resolution,
                                                       0.0, 0.0));
        } catch (FactoryException e) {
            throw new IOException(e);
        } catch (TransformException e) {
            throw new IOException(e);
        }

        product.setPreferredTileSize(S2Config.DEFAULT_JAI_TILE_SIZE, S2Config.DEFAULT_JAI_TILE_SIZE);
        product.setNumResolutionsMax(getConfig().getTileLayout(S2SpatialResolution.R10M.resolution).numResolutions);
        product.setAutoGrouping("sun:view:quality");



        List<BandInfo> bandInfoList = new ArrayList<>();

        List<S2Metadata.Tile> tileList = metadataHeader.getTileList();
        if (isAGranule) {
            tileList = tileList.stream().filter(p -> p.getId().equalsIgnoreCase(aFilter)).collect(Collectors.toList());
        }

        // Verify access to granule image files, and store absolute location
        for (S2BandInformation bandInformation : productCharacteristics.getBandInformations()) {
            HashMap<String, File> tileFileMap = new HashMap<>();
            for (S2Metadata.Tile tile : tileList) {
                S2OrthoGranuleDirFilename gf = S2OrthoGranuleDirFilename.create(tile.getId());
                if (gf != null) {

                    String imgFilename = String.format("GRANULE%s%s%s%s", File.separator, tile.getId(),
                            File.separator,
                            bandInformation.getImageFileTemplate()
                                    .replace("{{MISSION_ID}}", gf.missionID)
                                    .replace("{{SITECENTRE}}", gf.siteCentre)
                                    .replace("{{CREATIONDATE}}", gf.creationDate)
                                    .replace("{{ABSOLUTEORBIT}}", gf.absoluteOrbit)
                                    .replace("{{TILENUMBER}}", gf.tileNumber)
                                    .replace("{{RESOLUTION}}", String.format("%d", bandInformation.getResolution().resolution)));

                    logger.finer("Adding file " + imgFilename + " to band: " + bandInformation.getPhysicalBand());

                    File file = new File(productDir, imgFilename);
                    if (file.exists()) {
                        tileFileMap.put(tile.getId(), file);
                    } else {
                        logger.warning(String.format("Warning: missing file %s\n", file));
                    }
                }
            }

            if (!tileFileMap.isEmpty()) {
                BandInfo bandInfo = createBandInfoFromHeaderInfo(bandInformation, tileFileMap);
                bandInfoList.add(bandInfo);
            } else {
                logger.warning(String.format("Warning: no image files found for band %s\n", bandInformation.getPhysicalBand()));
            }
        }
        SystemUtils.LOG.fine(String.format("[timeprobe] product initialisation : %s ms", timeProbe.elapsed(TimeUnit.MILLISECONDS)));
        timeProbe.reset();

        if (!bandInfoList.isEmpty()) {
            addBands(product,
                     bandInfoList,
                     sceneDescription);
            SystemUtils.LOG.fine(String.format("[timeprobe] addBands : %s ms", timeProbe.elapsed(TimeUnit.MILLISECONDS)));
            timeProbe.reset();


            scaleBands(product, bandInfoList);
            SystemUtils.LOG.fine(String.format("[timeprobe] scaleBands : %s ms", timeProbe.elapsed(TimeUnit.MILLISECONDS)));
            timeProbe.reset();

            addVectorMasks(product, tileList, bandInfoList);
            SystemUtils.LOG.fine(String.format("[timeprobe] addVectorMasks : %s ms", timeProbe.elapsed(TimeUnit.MILLISECONDS)));
            timeProbe.reset();

            addIndexMasks(product, bandInfoList);
            SystemUtils.LOG.fine(String.format("[timeprobe] addIndexMasks : %s ms", timeProbe.elapsed(TimeUnit.MILLISECONDS)));
            timeProbe.reset();
        }

        if (!"Brief".equalsIgnoreCase(productCharacteristics.getMetaDataLevel())) {
            addTiePointGridBand(product, metadataHeader, sceneDescription, "sun_zenith", SUN_ZENITH_GRID_INDEX, "Solar zenith angle", "°");
            addTiePointGridBand(product, metadataHeader, sceneDescription, "sun_azimuth", SUN_AZIMUTH_GRID_INDEX, "Solar azimuth angle", "°");
            addTiePointGridBand(product, metadataHeader, sceneDescription, "view_zenith", VIEW_ZENITH_GRID_INDEX, "Viewing incidence zenith angle", "°");
            addTiePointGridBand(product, metadataHeader, sceneDescription, "view_azimuth", VIEW_AZIMUTH_GRID_INDEX, "Viewing incidence azimuth angle", "°");
            SystemUtils.LOG.fine(String.format("[timeprobe] addTiePointGridBand : %s ms", timeProbe.elapsed(TimeUnit.MILLISECONDS)));
            timeProbe.reset();
        }

        return product;
    }

    abstract protected int getMaskLevel();

    private void addBands(Product product, List<BandInfo> bandInfoList, S2OrthoSceneLayout sceneDescription) throws IOException {
        for (BandInfo bandInfo : bandInfoList) {
            Band band = addBand(product, bandInfo);
            band.setDescription(bandInfo.getBandInformation().getDescription());
            band.setUnit(bandInfo.getBandInformation().getUnit());

            double pixelSize = 0;
            if (isMultiResolution()) {
                pixelSize = (double) bandInfo.getBandInformation().getResolution().resolution;
            } else {
                pixelSize = (double) getProductResolution().resolution;
            }

            try {
                band.setGeoCoding(new CrsGeoCoding(CRS.decode(epsgCode),
                        band.getRasterWidth(),
                        band.getRasterHeight(),
                        sceneDescription.getSceneOrigin()[0],
                        sceneDescription.getSceneOrigin()[1],
                        pixelSize,
                        pixelSize,
                        0.0, 0.0));
            } catch (FactoryException e) {
                throw new IOException(e);
            } catch (TransformException e) {
                throw new IOException(e);
            }

            MultiLevelImageFactory mlif = new L1cSceneMultiLevelImageFactory(
                    sceneDescription,
                    Product.findImageToModelTransform(band.getGeoCoding()));

            band.setSourceImage(mlif.createSourceImage(bandInfo));

        }
    }

    private void scaleBands(Product product, List<BandInfo> bandInfoList) throws IOException {

        // In MultiResolution mode, all bands are kept at their native resolution
        if (isMultiResolution()) {
            return;
        }

        // Find a reference band for rescaling the bands at other resolution
        MultiLevelImage targetImage = null;
        for (BandInfo bandInfo : bandInfoList) {
            if (bandInfo.getBandInformation().getResolution() == getProductResolution()) {
                Band referenceBand = product.getBand(bandInfo.getBandInformation().getPhysicalBand());
                targetImage = referenceBand.getSourceImage();
                break;
            }
        }

        // If the product only has a subset of bands, we may not find what we are looking for
        if (targetImage == null) {
            String error = String.format("Products with no bands at %s m resolution currently cannot be read by the %s m reader", getProductResolution().resolution, getProductResolution().resolution);
            throw new IOException(error);
        }

        for (Band band : product.getBands()) {
            final MultiLevelImage sourceImage = band.getSourceImage();

            if (sourceImage.getWidth() == product.getSceneRasterWidth()
                    && sourceImage.getHeight() == product.getSceneRasterHeight()) {
                // Do not rescaled band which are already at the correct resolution
                continue;
            }

            ImageLayout imageLayout = new ImageLayout();
            ImageManager.getPreferredTileSize(product);
            final RenderingHints renderingHints = new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout);
            float[] scalings = new float[2];
            scalings[0] = product.getSceneRasterWidth() / (float) sourceImage.getWidth();
            scalings[1] = product.getSceneRasterHeight() / (float) sourceImage.getHeight();
            PlanarImage scaledImage = SourceImageScaler.scaleMultiLevelImage(targetImage, sourceImage, scalings, null, renderingHints,
                    band.getNoDataValue(),
                    Interpolation.getInstance(Interpolation.INTERP_NEAREST));
            band.setSourceImage(scaledImage);
        }
    }

    private void addIndexMasks(Product product, List<BandInfo> bandInfoList) {
        for (BandInfo bandInfo : bandInfoList) {
            if (bandInfo.getBandInformation() instanceof S2IndexBandInformation) {
                S2IndexBandInformation indexBandInformation = (S2IndexBandInformation) bandInfo.getBandInformation();
                IndexCoding indexCoding = indexBandInformation.getIndexCoding();
                product.getIndexCodingGroup().add(indexCoding);
                for (String indexName : indexCoding.getIndexNames()) {
                    int indexValue = indexCoding.getIndexValue(indexName);
                    String description = indexCoding.getIndex(indexName).getDescription();
                    Mask mask = Mask.BandMathsType.create("scl_" + indexName.toLowerCase(), description, product.getSceneRasterWidth(), product.getSceneRasterHeight(),
                            String.format("%s.raw == %d", indexBandInformation.getPhysicalBand(), indexValue), ColorIterator.next(), 0.5);
                    product.addMask(mask);
                }
            }
        }
    }

    private void addVectorMasks(Product product, List<S2Metadata.Tile> tileList, List<BandInfo> bandInfoList) throws IOException {
        for (MaskInfo maskInfo : MaskInfo.values()) {
            if (!maskInfo.isPresentAtLevel(getMaskLevel()))
                continue;

            if (!maskInfo.isPerBand()) {
                // cloud masks are provided once and valid for all bands
                addVectorMask(product, tileList, maskInfo, null, bandInfoList);
            }
            else {
                // for other masks, we have one mask instance for each spectral band
                for (BandInfo bandInfo : bandInfoList) {
                    if (bandInfo.getBandInformation() instanceof S2SpectralInformation) {
                        addVectorMask(product, tileList, maskInfo, (S2SpectralInformation)bandInfo.getBandInformation(), bandInfoList);
                    }
                }
            }
        }
    }

    private void addVectorMask(Product product, List<S2Metadata.Tile> tileList, MaskInfo maskInfo, S2SpectralInformation spectralInfo, List<BandInfo> bandInfoList) {
        List<EopPolygon> productPolygons = new ArrayList<>();

        for (S2Metadata.Tile tile : tileList) {
            for (S2Metadata.MaskFilename maskFilename : tile.getMaskFilenames()) {

                // We are only interested in a single mask main type
                if (!maskFilename.getType().equals(maskInfo.getMainType())) {
                    continue;
                }

                if (spectralInfo != null) {
                    // We are only interested in masks for a certain band
                    if (!maskFilename.getBandId().equals(String.format("%s", spectralInfo.getBandId()))) {
                        continue;
                    }
                }

                // Read all polygons from the mask file
                GmlFilter gmlFilter = new GmlFilter();
                List<EopPolygon> polygonsForTile = gmlFilter.parse(maskFilename.getName()).getSecond();

                // We are interested only in a single subtype
                polygonsForTile = polygonsForTile.stream().filter(p -> p.getType().equals(maskInfo.getSubType())).collect(Collectors.toList());

                // Merge polygons from this tile to product polygon list
                productPolygons.addAll(polygonsForTile);
            }
        }

        // TODO : why do we use this here ?
        final SimpleFeatureType type = Placemark.createGeometryFeatureType();
        // TODO : why "S2L1CMasks" ?
        final DefaultFeatureCollection collection = new DefaultFeatureCollection("S2L1CMasks", type);

        for (int index = 0; index < productPolygons.size(); index++) {
            Polygon polygon = productPolygons.get(index).getPolygon();

            Object[] data1 = {polygon, String.format("Polygon-%s", index)};
            SimpleFeatureImpl f1 = new SimpleFeatureImpl(data1, type, new FeatureIdImpl(String.format("F-%s", index)), true);
            collection.add(f1);
        }

        if (spectralInfo == null) {
            // This mask is not specific to a band
            // So we need one version of it for each resolution present in the band list
            for (S2SpatialResolution resolution : S2SpatialResolution.values()) {
                // Find a band with this resolution
                Band referenceBand = null;
                for (BandInfo bandInfo : bandInfoList) {
                    if (bandInfo.getBandInformation().getResolution() == resolution) {
                        referenceBand = product.getBand(bandInfo.getBandInformation().getPhysicalBand());
                        break;
                    }
                }

                // We may not find a band with this resolution
                if (referenceBand == null) {
                    continue;
                }

                // We need a different name for each resolution version
                String description = maskInfo.getDescription();
                String snapName = String.format("%s_%dm", maskInfo.getSnapName(), resolution.resolution);
                VectorDataNode vdn = new VectorDataNode(snapName, collection);
                vdn.setOwner(product);
                product.addMask(snapName,
                                vdn,
                                description,
                                maskInfo.getColor(),
                                maskInfo.getTransparency(),
                                referenceBand);
            }
        }
        else {
            // This mask is specific to a band
            Band referenceBand = product.getBand(spectralInfo.getPhysicalBand());
            String bandName = spectralInfo.getPhysicalBand();
            String snapName = maskInfo.getSnapNameForBand(bandName);
            String description = maskInfo.getDescriptionForBand(bandName);
            VectorDataNode vdn = new VectorDataNode(snapName, collection);
            vdn.setOwner(product);
            product.addMask(snapName,
                            vdn,
                            description,
                            maskInfo.getColor(),
                            maskInfo.getTransparency(),
                            referenceBand);
        }
    }

    private void addTiePointGridBand(Product product, S2Metadata metadataHeader, S2OrthoSceneLayout sceneDescription, String name, int tiePointGridIndex, String description, String unit) {
        final Band band = product.addBand(name, ProductData.TYPE_FLOAT32);
        band.setDescription(description);
        band.setUnit(unit);
        band.setSourceImage(new DefaultMultiLevelImage(new TiePointGridL1cSceneMultiLevelSource(sceneDescription,
                                                                                                metadataHeader,
                                                                                                Product.findImageToModelTransform(product.getSceneGeoCoding()),
                                                                                                4,
                                                                                                tiePointGridIndex)));
    }

    private TiePointGrid[] createL1cTileTiePointGrids(S2Metadata metadataHeader, String tileId) throws IOException {
        TiePointGrid[] tiePointGrid = null;
        S2Metadata.Tile tile = metadataHeader.getTile(tileId);
        S2Metadata.AnglesGrid anglesGrid = tile.getSunAnglesGrid();
        if (anglesGrid != null) {
            int gridHeight = tile.getSunAnglesGrid().getZenith().length;
            int gridWidth = tile.getSunAnglesGrid().getZenith()[0].length;
            float[] sunZeniths = new float[gridWidth * gridHeight];
            float[] sunAzimuths = new float[gridWidth * gridHeight];
            float[] viewingZeniths = new float[gridWidth * gridHeight];
            float[] viewingAzimuths = new float[gridWidth * gridHeight];
            Arrays.fill(viewingZeniths, Float.NaN);
            Arrays.fill(viewingAzimuths, Float.NaN);
            S2Metadata.AnglesGrid sunAnglesGrid = tile.getSunAnglesGrid();
            S2Metadata.AnglesGrid[] viewingIncidenceAnglesGrids = tile.getViewingIncidenceAnglesGrids();
            for (int y = 0; y < gridHeight; y++) {
                for (int x = 0; x < gridWidth; x++) {
                    final int index = y * gridWidth + x;
                    sunZeniths[index] = sunAnglesGrid.getZenith()[y][x];
                    sunAzimuths[index] = sunAnglesGrid.getAzimuth()[y][x];
                    for (S2Metadata.AnglesGrid grid : viewingIncidenceAnglesGrids) {
                        try {
                            if (y < grid.getZenith().length) {
                                if (x < grid.getZenith()[y].length) {
                                    if (!Float.isNaN(grid.getZenith()[y][x]) && !Float.isInfinite(grid.getZenith()[y][x])) {
                                        viewingZeniths[index] = grid.getZenith()[y][x];
                                    }
                                }
                            }

                            if (y < grid.getAzimuth().length) {
                                if (x < grid.getAzimuth()[y].length) {
                                    if (!Float.isNaN(grid.getAzimuth()[y][x]) && !Float.isInfinite(grid.getZenith()[y][x])) {
                                        viewingAzimuths[index] = grid.getAzimuth()[y][x];
                                    }
                                }
                            }

                        } catch (Exception e) {
                            // {@report "Solar info problem"}
                            logger.severe(StackTraceUtils.getStackTrace(e));
                        }
                    }
                }
            }
            tiePointGrid = new TiePointGrid[]{
                    createTiePointGrid("sun_zenith", gridWidth, gridHeight, sunZeniths),
                    createTiePointGrid("sun_azimuth", gridWidth, gridHeight, sunAzimuths),
                    createTiePointGrid("view_zenith", gridWidth, gridHeight, viewingZeniths),
                    createTiePointGrid("view_azimuth", gridWidth, gridHeight, viewingAzimuths)
            };
        }
        return tiePointGrid;
    }

    private TiePointGrid createTiePointGrid(String name, int gridWidth, int gridHeight, float[] values) {
        double offset = 0.0;
        double subSampling = 500.0F / ((double)getProductResolution().resolution / (double)S2SpatialResolution.R10M.resolution);
        final TiePointGrid tiePointGrid = new TiePointGrid(name, gridWidth, gridHeight, offset, offset, subSampling, subSampling, values);
        tiePointGrid.setNoDataValue(Double.NaN);
        tiePointGrid.setNoDataValueUsed(true);
        return tiePointGrid;
    }

    private BandInfo createBandInfoFromHeaderInfo(S2BandInformation bandInformation, Map<String, File> tileFileMap) {
        S2SpatialResolution spatialResolution = bandInformation.getResolution();
        return new BandInfo(tileFileMap,
                            bandInformation,
                            getConfig().getTileLayout(spatialResolution.resolution));
    }

    static File getProductDir(File productFile) throws IOException {
        final File resolvedFile = productFile.getCanonicalFile();
        if (!resolvedFile.exists()) {
            throw new FileNotFoundException("File not found: " + productFile);
        }

        if (productFile.getParentFile() == null) {
            return new File(".").getCanonicalFile();
        }

        return productFile.getParentFile();
    }

    private abstract class MultiLevelImageFactory {
        protected final AffineTransform imageToModelTransform;

        protected MultiLevelImageFactory(AffineTransform imageToModelTransform) {
            this.imageToModelTransform = imageToModelTransform;
        }

        public abstract MultiLevelImage createSourceImage(BandInfo bandInfo);
    }

    private class L1cSceneMultiLevelImageFactory extends MultiLevelImageFactory {

        private final S2OrthoSceneLayout sceneDescription;

        public L1cSceneMultiLevelImageFactory(S2OrthoSceneLayout sceneDescription, AffineTransform imageToModelTransform) {
            super(imageToModelTransform);

            SystemUtils.LOG.fine("Model factory: " + ToStringBuilder.reflectionToString(imageToModelTransform));

            this.sceneDescription = sceneDescription;
        }

        @Override
        public MultiLevelImage createSourceImage(BandInfo bandInfo) {
            BandL1cSceneMultiLevelSource bandScene = new BandL1cSceneMultiLevelSource(sceneDescription, bandInfo, imageToModelTransform);
            SystemUtils.LOG.fine("BandScene: " + bandScene);
            return new DefaultMultiLevelImage(bandScene);
        }
    }


    /**
     * A MultiLevelSource for a scene made of multiple L1C tiles.
     */
    private abstract class AbstractL1cSceneMultiLevelSource extends AbstractMultiLevelSource {
        protected final S2OrthoSceneLayout sceneDescription;

        AbstractL1cSceneMultiLevelSource(S2OrthoSceneLayout sceneDescription, S2SpatialResolution bandResolution, AffineTransform imageToModelTransform, int numResolutions) {
            super(new DefaultMultiLevelModel(numResolutions,
                                             imageToModelTransform,
                                             sceneDescription.getSceneDimension(bandResolution).width,
                                             sceneDescription.getSceneDimension(bandResolution).height));
            this.sceneDescription = sceneDescription;
        }
    }

    /**
     * A MultiLevelSource used by bands for a scene made of multiple L1C tiles.
     */
    private final class BandL1cSceneMultiLevelSource extends AbstractL1cSceneMultiLevelSource {
        private final BandInfo bandInfo;

        public BandL1cSceneMultiLevelSource(S2OrthoSceneLayout sceneDescription, BandInfo bandInfo, AffineTransform imageToModelTransform) {
            super(sceneDescription, bandInfo.getBandInformation().getResolution(), imageToModelTransform, bandInfo.getImageLayout().numResolutions);
            this.bandInfo = bandInfo;
        }

        protected RenderedImage createImage(int level) {
            ArrayList<RenderedImage> tileImages = new ArrayList<>();

            for (String tileId : sceneDescription.getTileIds()) {
                /*
                 * Get the a PlanarImage of the tile at native resolution, with a [0,0] origin
                 */
                File imageFile = bandInfo.getTileIdToFileMap().get(tileId);

                // Get the band native resolution
                S2SpatialResolution bandNativeResolution = bandInfo.getBandInformation().getResolution();
                // Get the position of the L1C tile in full scene at level 0
                Rectangle l1cTileRectangleL0 = sceneDescription.getTilePositionInScene(tileId, bandNativeResolution);
                // Get the position of the L1C tile in full scene at current requested level
                //Rectangle l1cTileRectangle = DefaultMultiLevelSource.getLevelImageBounds(l1cTileRectangleL0, getModel().getScale(level));

                /*
                 * Iterate over internal JP2 tiles
                 */
                TileLayout l1cTileLayout = bandInfo.getImageLayout();
                for (int x = 0; x < l1cTileLayout.numXTiles; x++) {
                    for (int y = 0; y < l1cTileLayout.numYTiles; y++) {

                        // Get the position of the internal JP2 tile of L1C tile in full scene at current requested level
                        /*
                        Rectangle relativePositionL0 = new Rectangle(x*l1cTileLayout.width, y * l1cTileLayout.height, l1cTileLayout.width, l1cTileLayout.height);
                        Rectangle relativePosition = DefaultMultiLevelSource.getLevelImageBounds(relativePositionL0, getModel().getScale(level));
                        Rectangle absolutePosition = (Rectangle)relativePosition.clone();
                        absolutePosition.translate(l1cTileRectangle.x, l1cTileRectangle.y);
                        */

                        Rectangle internalJp2TileRectangleL0 = new Rectangle(
                                l1cTileRectangleL0.x + x * l1cTileLayout.tileWidth,
                                l1cTileRectangleL0.y + y * l1cTileLayout.tileHeight,
                                l1cTileLayout.tileWidth,
                                l1cTileLayout.tileHeight);
                        Rectangle internalJp2TileRectangle = DefaultMultiLevelSource.getLevelImageBounds(internalJp2TileRectangleL0, getModel().getScale(level));

                        PlanarImage opImage;
                        try {
                            TileLayout currentLayout = l1cTileLayout;
                            // The edge tiles dimensions may be less than the dimensions from JP2 header
                            // because the size of the image is not necessarily a multiple of the tile size
                            if (y == l1cTileLayout.numYTiles - 1 || x == l1cTileLayout.numXTiles - 1) {
                                currentLayout = new TileLayout(l1cTileLayout.width, l1cTileLayout.height,
                                        l1cTileLayout.width - x * l1cTileLayout.tileWidth, l1cTileLayout.height - y * l1cTileLayout.tileHeight,
                                        l1cTileLayout.numXTiles, l1cTileLayout.numYTiles, l1cTileLayout.numResolutions);
                            }
                            opImage = JP2TileOpImage.create(imageFile.toPath(), getCacheDir().toPath(),
                                    0, y, x, currentLayout, getModel(), DataBuffer.TYPE_USHORT, level);

                            if (opImage != null) {
                                opImage = TranslateDescriptor.create(opImage,
                                        (float) (internalJp2TileRectangle.x),
                                        (float) (internalJp2TileRectangle.y),
                                        Interpolation.getInstance(Interpolation.INTERP_NEAREST), null);
                            }
                        } catch (IOException ex) {
                            opImage = ConstantDescriptor.create((float) internalJp2TileRectangle.width, (float) internalJp2TileRectangle.height, new Number[]{0}, null);
                        }
                        tileImages.add(opImage);
                    }
                }
                if (tileImages.isEmpty()) {
                    logger.warning("No tile images for mosaic");
                    return null;
                }
            }

            if (tileImages.isEmpty()) {
                logger.warning("No tile images for mosaic");
                return null;
            }

            ImageLayout imageLayout = new ImageLayout();
            imageLayout.setMinX(0);
            imageLayout.setMinY(0);
            imageLayout.setTileWidth(S2Config.DEFAULT_JAI_TILE_SIZE);
            imageLayout.setTileHeight(S2Config.DEFAULT_JAI_TILE_SIZE);
            imageLayout.setTileGridXOffset(0);
            imageLayout.setTileGridYOffset(0);

            RenderedOp mosaicOp = MosaicDescriptor.create(tileImages.toArray(new RenderedImage[tileImages.size()]),
                                                          MosaicDescriptor.MOSAIC_TYPE_OVERLAY,
                                                          null, null, new double[][]{{1.0}}, new double[]{S2Config.FILL_CODE_MOSAIC_BG},
                                                          new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout));

            /*
             * Adjust size of output image
             */
            // Get dimension at level 0
            S2SpatialResolution bandNativeResolution = bandInfo.getBandInformation().getResolution();
            Dimension bandDimensionLevel0 = sceneDescription.getSceneDimension(bandNativeResolution);
            // Compute dimension at level 'level' according to "J2K rule"
            Rectangle bandRectangle = DefaultMultiLevelSource.getLevelImageBounds(
                    new Rectangle(bandDimensionLevel0.width, bandDimensionLevel0.height),
                    getModel().getScale(level));
            // Crop accordingly
            RenderedOp croppedMosaic = CropDescriptor.create(mosaicOp,
                                                             0.0f, 0.0f, (float) bandRectangle.width, (float) bandRectangle.height,
                                                             new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout));

            return croppedMosaic;
        }

        @Override
        public String toString() {
            return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
        }
    }

    /**
     * A MultiLevelSource used by bands for a scene made of multiple L1C tiles.
     */
    private final class TiePointGridL1cSceneMultiLevelSource extends AbstractL1cSceneMultiLevelSource {

        private final S2Metadata metadata;
        private final int tiePointGridIndex;
        private HashMap<String, TiePointGrid[]> tiePointGridsMap;

        public TiePointGridL1cSceneMultiLevelSource(S2OrthoSceneLayout sceneDescription, S2Metadata metadata, AffineTransform imageToModelTransform, int numResolutions, int tiePointGridIndex) {
            super(sceneDescription, getProductResolution(), imageToModelTransform, numResolutions);
            this.metadata = metadata;
            this.tiePointGridIndex = tiePointGridIndex;
            tiePointGridsMap = new HashMap<>();
        }

        protected PlanarImage createL1cTileImage(String tileId, int level) throws IOException {
            PlanarImage tiePointGridL1CTileImage = null;
            TiePointGrid[] tiePointGrids = tiePointGridsMap.get(tileId);
            if (tiePointGrids == null) {
                tiePointGrids = createL1cTileTiePointGrids(metadata, tileId);
                if (tiePointGrids != null) {
                    tiePointGridsMap.put(tileId, tiePointGrids);
                }
            }

            if (tiePointGrids != null) {
                tiePointGridL1CTileImage = (PlanarImage) tiePointGrids[tiePointGridIndex].getSourceImage().getImage(level);
            }

            return tiePointGridL1CTileImage;
        }

        @Override
        protected RenderedImage createImage(int level) {
            ArrayList<RenderedImage> tileImages = new ArrayList<>();

            for (String tileId : sceneDescription.getTileIds()) {
                PlanarImage opImage = null;
                try {
                    opImage = createL1cTileImage(tileId, level);
                } catch (IOException e) {
                    logger.severe("Unable to create L1cTileImage");
                }

                /*
                 * Translate the [0,0] image w.r.t its pixel position in the scene.
                 */
                // Get the position in scene at level 0 and or product resolution
                Rectangle tileRectangle = sceneDescription.getTilePositionInScene(tileId, getProductResolution());
                // Compute position in scene for current level
                Rectangle scaledRectangle = DefaultMultiLevelSource.getLevelImageBounds(tileRectangle, getModel().getScale(level));
                // Apply tile translation
                opImage = TranslateDescriptor.create(opImage,
                        (float) scaledRectangle.x,
                        (float) scaledRectangle.y,
                        Interpolation.getInstance(Interpolation.INTERP_NEAREST), null);

                logger.fine(String.format("Translate descriptor: %s", ToStringBuilder.reflectionToString(opImage)));
                logger.log(Level.parse(S2Config.LOG_SCENE), String.format("opImage added for level %d at (%d,%d) with size (%d,%d)%n", level, opImage.getMinX(), opImage.getMinY(), opImage.getWidth(), opImage.getHeight()));

                // Feed the image list for mosaic
                tileImages.add(opImage);
            }

            if (tileImages.isEmpty()) {
                logger.warning("no tile images for mosaic");
                return null;
            }

            ImageLayout imageLayout = new ImageLayout();
            imageLayout.setMinX(0);
            imageLayout.setMinY(0);
            imageLayout.setTileWidth(S2Config.DEFAULT_JAI_TILE_SIZE);
            imageLayout.setTileHeight(S2Config.DEFAULT_JAI_TILE_SIZE);
            imageLayout.setTileGridXOffset(0);
            imageLayout.setTileGridYOffset(0);

            RenderedOp mosaicOp = MosaicDescriptor.create(tileImages.toArray(new RenderedImage[tileImages.size()]),
                                                          MosaicDescriptor.MOSAIC_TYPE_OVERLAY,
                                                          null, null, new double[][]{{1.0}}, new double[]{S2Config.FILL_CODE_MOSAIC_BG},
                                                          new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout));

            logger.fine(String.format("mosaicOp created for level %d at (%d,%d)%n", level, mosaicOp.getMinX(), mosaicOp.getMinY()));
            logger.fine(String.format("mosaicOp size: (%d,%d)%n", mosaicOp.getWidth(), mosaicOp.getHeight()));

            /*
             * Adjust size of output image
             */
            // Get dimension at level 0
            Dimension dimensionLevel0 = sceneDescription.getSceneDimension(getProductResolution());
            // Compute dimension at level 'level' according to "J2K rule"
            Rectangle rectangle = DefaultMultiLevelSource.getLevelImageBounds(
                    new Rectangle(dimensionLevel0.width, dimensionLevel0.height),
                    getModel().getScale(level));
            // Crop accordingly
            RenderedOp croppedMosaic = CropDescriptor.create(mosaicOp,
                    0.0f, 0.0f, (float) rectangle.width, (float) rectangle.height,
                    new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout));

            return croppedMosaic;
        }
    }


    @Override
    protected String[] getBandNames(S2SpatialResolution resolution) {
        String[] bandNames;

        switch (resolution) {
            case R10M:
                bandNames = new String[]{"B02", "B03", "B04", "B08"};
                break;
            case R20M:
                bandNames = new String[]{"B05", "B06", "B07", "B8A", "B11", "B12"};
                break;
            case R60M:
                bandNames = new String[]{"B01", "B09", "B10"};
                break;
            default:
                SystemUtils.LOG.warning("Invalid resolution: " + resolution);
                bandNames = null;
                break;
        }

        return bandNames;
    }
}

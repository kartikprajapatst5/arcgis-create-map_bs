package com.accuritas.arcgis.service;

import static com.accuritas.arcgis.util.Tools.close;
import static com.accuritas.arcgis.util.Tools.createPoint;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.io.FileUtils;

import com.accuritas.arcgis.api.forensic.CreateMapRequest;
import com.accuritas.arcgis.api.forensic.LayerType;
import com.accuritas.arcgis.api.forensic.Spotter;
import com.accuritas.arcgis.api.forensic.Station;
import com.accuritas.arcgis.entity.Ellipsoid;
import com.accuritas.arcgis.entity.GeodeticCalculator;
import com.accuritas.arcgis.entity.GeodeticCurve;
import com.accuritas.arcgis.entity.GlobalCoordinates;
import com.esri.arcgis.carto.AnnotateLayerPropertiesCollection;
import com.esri.arcgis.carto.FeatureLayer;
import com.esri.arcgis.carto.GroupLayer;
import com.esri.arcgis.carto.IAnnotateLayerProperties;
import com.esri.arcgis.carto.ILayer;
import com.esri.arcgis.carto.IRasterIdentifyObjProxy;
import com.esri.arcgis.carto.Map;
import com.esri.arcgis.carto.MapDocument;
import com.esri.arcgis.carto.RasterLayer;
import com.esri.arcgis.datasourcesGDB.FgdbFeatureClassName;
import com.esri.arcgis.datasourcesGDB.FileGDBWorkspaceFactory;
import com.esri.arcgis.geodatabase.FeatureClass;
import com.esri.arcgis.geodatabase.IFeature;
import com.esri.arcgis.geodatabase.IFeatureCursor;
import com.esri.arcgis.geodatabase.QueryFilter;
import com.esri.arcgis.geodatabase.SpatialFilter;
import com.esri.arcgis.geodatabase.Workspace;
import com.esri.arcgis.geodatabase.WorkspaceName;
import com.esri.arcgis.geodatabase.esriSpatialRelEnum;
import com.esri.arcgis.geometry.Envelope;
import com.esri.arcgis.geometry.Point;
import com.esri.arcgis.geometry.Polygon;
import com.esri.arcgis.geometry.ProjectedCoordinateSystem;
import com.esri.arcgis.system.IArray;
import com.esri.arcgis.system.UnitConverter;
import com.esri.arcgis.system._WKSPoint;

public class ForensicService {
	
	/** Class Members **/
	private final static String MAP_PASS = "pass";

	public ForensicService() {
		super();
	}
	
	public void createMapFile(CreateMapRequest request, String mapFileName, String customGDBName, String outputFileName) throws IOException {
		final File mapFile = new File(mapFileName);
		
		// Import data
		this.createMap(request.getLat(), request.getLon(), request.getRadius(), request.getDate(), request.getNos(), request.getCoop(), request.getAsos(), request.getNws(), mapFile, new File(customGDBName));
		if(request.getSpotters() != null && request.getSpotters().size()>0) {
			this.createSpotters(customGDBName, request.getSpotters());
		}
		if(request.getStationsToRemove() != null && request.getStationsToRemove().size()>0) {
			this.removeStations(mapFile,request.getStationsToRemove());
		}
		
		// Export to file
		if(request.isImage()) {
			this.createImage(mapFile,outputFileName);
		} else {
			this.createText(mapFile,outputFileName);
		}
	}

	/**
	 * Creates a set of spotter rows in the target dataset.
	 * 
	 * @param id
	 * @param latitudes
	 * @param longitudes
	 * @param names
	 * @param elevations
	 * @return true on success, false on failure
	 */
	private boolean createSpotters(String customGDBName, List<Spotter> spotters) {
		FileGDBWorkspaceFactory fgdbwf = null;
		Workspace w = null;
		try {
			// Open dataset
			fgdbwf = new FileGDBWorkspaceFactory();
			w = new Workspace(fgdbwf.openFromFile(customGDBName, 0));
			w.startEditing(true);
			w.startEditOperation();

			// Create rows
			for(Spotter spotter : spotters) {
				final HashMap<String, Object> hm = new HashMap<String, Object>();
				hm.put("name", spotter.getName() == null ? "" : spotter.getName());
				hm.put("elevation", spotter.getElevation()==null ? new Double(0) : new Double(spotter.getElevation()));
				final FeatureClass fc = new FeatureClass(w.openFeatureClass("Spotters"));
				createPoint(fc, spotter.getLon(), spotter.getLat(), hm);
				fc.release();
			}

			w.stopEditOperation();
			w.stopEditing(true);
			return true;
		} catch (IOException ioe) {
			ioe.printStackTrace();
			return false;
		} finally {
			close(w);
			close(fgdbwf);
		}
	}
	
	/**
	 * Create the map document for the argument location and radius, returning
	 * the textual information
	 * 
	 * @param id
	 * @param lat
	 * @param lon
	 * @param radius
	 * @param date
	 *            YYYYmmdd
	 * @param nos
	 * @param coop
	 * @param asos
	 * @param nws
	 * @return textual caption for map
	 */
	private boolean createMap(double lat, double lon, double radius, int date, Boolean nos, Boolean coop, Boolean asos, Boolean nws, File mapFile, File customGDB) {
		// Set up file structure
		MapDocument md = null;
		Map m = null;
		Workspace w = null;
		FileGDBWorkspaceFactory fgdbwf = null;
		Envelope mapExtent = null;
		Point p = null;
		FeatureLayer nosLayer = null;
		FeatureLayer coopLayer = null;
		FeatureLayer asosLayer = null;
		FeatureLayer awosLayer = null;
		FeatureLayer crnLayer = null;
		FeatureLayer nwsLayer = null;
		FeatureLayer poiLayer = null;
		FeatureLayer spottersLayer = null;
		WorkspaceName wn = null;
		FgdbFeatureClassName fcn = null;
		UnitConverter uc = null;
		try {
			// copy basemap to "id.mxd"
			md = new MapDocument();
			md.open(mapFile.getPath(), MAP_PASS);

			// recenter and zoom map
			m = (Map) md.getMap(0);
			uc = new UnitConverter();
			mapExtent = (Envelope) m.getExtent();
			p = new Point();
			// Locate Point based on converted input units
			if (m.getSpatialReference() instanceof ProjectedCoordinateSystem) {
				_WKSPoint wksPoint = new _WKSPoint();
				wksPoint.x = lon;
				wksPoint.y = lat;
				_WKSPoint[][] wksPoints = new _WKSPoint[][] { { wksPoint } };
				((ProjectedCoordinateSystem) m.getSpatialReference()).forward(wksPoints);
				p.setX(wksPoints[0][0].x);
				p.setY(wksPoints[0][0].y);
			} else {
				p.setX(lon);
				p.setY(lat);
			}
			mapExtent.centerAt(p);

			// Convert from radius units (mi) to the map units of the input map
			radius = uc.convertUnits(radius, com.esri.arcgis.system.esriUnits.esriMiles, m.getMapUnits());
			double scale = Math.max((2 * radius) / mapExtent.getWidth(), (2 * radius) / mapExtent.getHeight());
			mapExtent.scale(p, scale, scale);
			m.setExtent(mapExtent);
			//m.refresh();

			// Get our layers
			for (int i = 0; i < m.getLayerCount(); i++) {
				ILayer l = m.getLayer(i);
				if (l instanceof FeatureLayer) {
					if (LayerType.NOS.name().equalsIgnoreCase(l.getName())) {
						nosLayer = (FeatureLayer) l;
					} else if (LayerType.COOP.name().equalsIgnoreCase(l.getName())) {
						coopLayer = (FeatureLayer) l;
					} else if (LayerType.ASOS.name().equalsIgnoreCase(l.getName())) {
						asosLayer = (FeatureLayer) l;
					} else if (LayerType.AWOS.name().equalsIgnoreCase(l.getName())) {
						awosLayer = (FeatureLayer) l;
					} else if (LayerType.CRN.name().equalsIgnoreCase(l.getName())) {
						crnLayer = (FeatureLayer) l;
					} else if ("NWS".equalsIgnoreCase(l.getName())) {
						nwsLayer = (FeatureLayer) l;
					} else if ("POI".equalsIgnoreCase(l.getName())) {
						poiLayer = (FeatureLayer) l;
					} else if ("Spotters".equalsIgnoreCase(l.getName())) {
						spottersLayer = (FeatureLayer) l;
					}
				}
			}

			// Set visible layers
			if (nos==null || !nos.booleanValue()) {
				if(nosLayer!=null) {
					nosLayer.setVisible(false);
				}
			}
			if (coop==null || !coop.booleanValue()) {
				if(coopLayer!=null) {
					coopLayer.setVisible(false);
				}
			}
			if (asos==null || !asos.booleanValue()) {
				if(asosLayer!=null) {
					asosLayer.setVisible(false);
				}
				
				// Note that AWOS is shown or hidden based on ASOS property
				if(awosLayer!=null) {
					awosLayer.setVisible(false);
				}
				
				// Note that CRN is shown or hidden based on ASOS property
				if(crnLayer != null) {
					crnLayer.setVisible(false);
				}
			}
			if (nws==null || !nws.booleanValue()) {
				if(nwsLayer!=null) {
					nwsLayer.setVisible(false);
				}
			}
			//m.refresh();

			// Create POI center
			fgdbwf = new FileGDBWorkspaceFactory();
			w = new Workspace(fgdbwf.openFromFile(customGDB.getPath(), 0));
			w.startEditing(true);
			w.startEditOperation();
			final FeatureClass poi = new FeatureClass(w.openFeatureClass("POI"));
			createPoint(poi, lon, lat, (String) null);
			poi.release();
			w.stopEditOperation();
			w.stopEditing(true);
			//m.refresh();

			// Set visible date
			if (nosLayer != null) {
				nosLayer.setDefinitionExpression(this.getWhereClauseNOS(date));
				IAnnotateLayerProperties alp = this.getALP(nosLayer);
				if (alp != null) {
					alp.setWhereClause(this.getWhereClauseNOS(date));
				}
			}
			if (coopLayer != null) {
				coopLayer.setDefinitionExpression(this.getWhereClauseCOOP(date));
				IAnnotateLayerProperties alp = this.getALP(coopLayer);
				if (alp != null) {
					alp.setWhereClause(this.getWhereClauseCOOP(date));
				}
			}
			if (asosLayer != null) {
				asosLayer.setDefinitionExpression(this.getWhereClauseASOS(date));
				IAnnotateLayerProperties alp = this.getALP(asosLayer);
				if (alp != null) {
					alp.setWhereClause(this.getWhereClauseASOS(date));
				}
			}
			if (awosLayer != null) {
				// Note that AWOS has no date information
			}
			if (crnLayer != null) {
				crnLayer.setDefinitionExpression(this.getWhereClauseCRN(date));
				IAnnotateLayerProperties alp = this.getALP(crnLayer);
				if (alp != null) {
					alp.setWhereClause(this.getWhereClauseCRN(date));
				}
			}
			if (nwsLayer != null) {
				nwsLayer.setDefinitionExpression(this.getWhereClauseNWS(date));
				IAnnotateLayerProperties alp = this.getALP(nwsLayer);
				if (alp != null) {
					alp.setWhereClause(this.getWhereClauseNWS(date));
				}
			}
			//m.refresh();

			// save map
			md.save(true, false);
			return true;
		} catch (IOException ioe) {
			ioe.printStackTrace();
			return false;
		} finally {
			close(uc);
			close(fcn);
			close(wn);
			close(nosLayer);
			close(coopLayer);
			close(asosLayer);
			close(awosLayer);
			close(crnLayer);
			close(nwsLayer);
			close(poiLayer);
			close(spottersLayer);
			close(p);
			close(mapExtent);
			close(fgdbwf);
			close(w);
			close(m);
			close(md);
		}
	}

	private boolean removeStation(Station station, FeatureLayer layer) {
		try {
			if (layer != null) {
				final StringBuilder clause = new StringBuilder();
				
				// Add existing clause if present
				final String existingLayerDefinitionExpression = layer.getDefinitionExpression();
				if (existingLayerDefinitionExpression != null) {
					clause.append(existingLayerDefinitionExpression);
				}
				
				// Append as necessary
				if (clause.length() > 0) {
					clause.append(" AND ");
				}
				clause.append("OBJECTID <> ");
				clause.append(station.getId());
				layer.setDefinitionExpression(clause.toString());
				return true;
			}
		} catch (Exception e) {
			
		}
		return false;
	}

	/**
	 * Remove the argument OID from the argument layer on the argument map.
	 * 
	 * @param id
	 * @param layerName
	 * @param oid
	 * @return true on success
	 */
	private void removeStations(File mapFile, List<Station> stations) {
		MapDocument md = null;
		Map m = null;
		try {
			// Open Map
			md = new MapDocument();
			md.open(mapFile.getPath(), MAP_PASS);
			m = (Map) md.getMap(0);
			
			// Create layer cache
			final java.util.Map<LayerType,FeatureLayer> cache = new HashMap<>();
			for (int i = 0; i < m.getLayerCount(); i++) {
				final ILayer l = m.getLayer(i);
				if (l instanceof FeatureLayer) {
					for(Station station : stations) {
						// Add to cache if match found and not already present
						if(cache.get(station.getLayerType())==null && station.getLayerType().name().equalsIgnoreCase(l.getName())) {
							cache.put(station.getLayerType(), (FeatureLayer) l);
						}
					}
				}
			}
			// Remove each station
			for(Station station : stations) {
				removeStation(station,cache.get(station.getLayerType()));
			}
			md.save(true, false);
			
			// Clean up objects
			for(FeatureLayer fl : cache.values()) {
				close(fl);
			}
			cache.clear();
		} catch (IOException ioe) {
			
		} finally {
			close(m);
			close(md);
		}
	}
	
	/**
	 * Generate the text for a given map id from the map contents.
	 * 
	 * @param id
	 * @return formatted String for map comments
	 */
	private String createText(File mapFile, String outputFileName) {
		MapDocument md = null;
		Map m = null;
		final StringBuilder sb = new StringBuilder();
		try {
			// Open Map
			md = new MapDocument();
			md.open(mapFile.getPath(), MAP_PASS);
			m = (Map) md.getMap(0);

			// Get our layers
			FeatureLayer nosLayer = null;
			FeatureLayer coopLayer = null;
			FeatureLayer asosLayer = null;
			FeatureLayer awosLayer = null;
			FeatureLayer crnLayer = null;
			FeatureLayer nwsLayer = null;
			FeatureLayer spottersLayer = null;
			FeatureLayer poiLayer = null;
			RasterLayer elevationLayer = null;

			for (int i = 0; i < m.getLayerCount(); i++) {
				ILayer l = m.getLayer(i);
				if (l instanceof FeatureLayer) {
					if (LayerType.NOS.name().equalsIgnoreCase(l.getName())) {
						nosLayer = (FeatureLayer) l;
					} else if (LayerType.COOP.name().equalsIgnoreCase(l.getName())) {
						coopLayer = (FeatureLayer) l;
					} else if (LayerType.ASOS.name().equalsIgnoreCase(l.getName())) {
						asosLayer = (FeatureLayer) l;
					} else if (LayerType.AWOS.name().equalsIgnoreCase(l.getName())) {
						awosLayer = (FeatureLayer) l;
					} else if (LayerType.CRN.name().equalsIgnoreCase(l.getName())) {
						crnLayer = (FeatureLayer) l;
					} else if ("NWS".equalsIgnoreCase(l.getName())) {
						nwsLayer = (FeatureLayer) l;
					} else if ("Spotters".equalsIgnoreCase(l.getName())) {
						spottersLayer = (FeatureLayer) l;
					} else if ("POI".equalsIgnoreCase(l.getName())) {
						poiLayer = (FeatureLayer) l;
					}
				} else if (l instanceof GroupLayer && l.getName().contains("SRTM")) {
					if (((GroupLayer) l).getCount() > 0 && ((GroupLayer) l).getLayer(0) instanceof RasterLayer) {
						elevationLayer = (RasterLayer) ((GroupLayer) l).getLayer(0);
					}
				}
			}

			// Examine each layer to find points within area
			final Envelope mapExtent = (Envelope) m.getExtent();
			final SpatialFilter sf = new SpatialFilter();
			sf.setSpatialRel(esriSpatialRelEnum.esriSpatialRelIntersects);
			sf.setGeometryByRef(mapExtent);
			final UnitConverter uc = new UnitConverter();
			final GeodeticCalculator gc = new GeodeticCalculator();

			// Header Row
			sb.append("Name\tElevation\tDistance\n");

			Point poiPoint = null;
			if (poiLayer != null && poiLayer.isVisible()) {
				IFeatureCursor ifc = poiLayer.search(new QueryFilter(), true);
				for (IFeature feature = ifc.nextFeature(); feature != null; feature = ifc.nextFeature()) {
					// POI is map center
					double distance = 0;

					// Static name
					sb.append("Point of Interest");
					sb.append("\t");
					// derive elevation
					poiPoint = (Point) feature.getShape();
					IArray ia = elevationLayer.identify(poiPoint);
					if (ia != null && ia.getCount() > 0) {
						IRasterIdentifyObjProxy riop = new IRasterIdentifyObjProxy(ia.getElement(0));

						sb.append(new DecimalFormat("#0.00").format(uc.convertUnits(Double.parseDouble(riop.getName()), com.esri.arcgis.system.esriUnits.esriMeters, com.esri.arcgis.system.esriUnits.esriFeet)) + "ft");
					}
					sb.append("\t");
					sb.append(new DecimalFormat("#0.00").format(distance) + "mi");
					sb.append("\t");
					sb.append("");
					sb.append("\t");
					sb.append("");
					sb.append("\n");
				}
			}
			if (nosLayer != null && nosLayer.isVisible()) {
				IAnnotateLayerProperties alp = this.getALP(nosLayer);
				sf.setWhereClause(alp == null ? "" : alp.getWhereClause());

				IFeatureCursor ifc = nosLayer.search(sf, true);
				for (IFeature feature = ifc.nextFeature(); feature != null; feature = ifc.nextFeature()) {
					Point p = (Point) feature.getShape();
					// Distance from p to center along a curve
					double distance = 0;
					if (poiPoint != null) {
						GlobalCoordinates gc1 = new GlobalCoordinates(p.getY(), p.getX());
						GlobalCoordinates gc2 = new GlobalCoordinates(poiPoint.getY(), poiPoint.getX());
						GeodeticCurve curve = gc.calculateGeodeticCurve(Ellipsoid.WGS84, gc1, gc2);
						distance = curve.getEllipsoidalDistance();
						distance = uc.convertUnits(distance, com.esri.arcgis.system.esriUnits.esriMeters, com.esri.arcgis.system.esriUnits.esriMiles);
					}

					sb.append(feature.getValue(3).toString());
					sb.append("\t");
					sb.append("0ft");
					sb.append("\t");
					sb.append(new DecimalFormat("#0.00").format(distance) + "mi");
					sb.append("\t");
					sb.append(nosLayer.getName());
					sb.append("\t");
					sb.append(feature.getValue(0).toString());
					sb.append("\n");
				}
			}
			if (coopLayer != null && coopLayer.isVisible()) {
				IAnnotateLayerProperties alp = this.getALP(coopLayer);
				sf.setWhereClause(alp == null ? "" : alp.getWhereClause());

				IFeatureCursor ifc = coopLayer.search(sf, true);
				for (IFeature feature = ifc.nextFeature(); feature != null; feature = ifc.nextFeature()) {
					Point p;
					if (feature.getShape() instanceof Point) {
						p = (Point) feature.getShape();
					} else {
						p = (Point) ((Envelope) feature.getShape().getEnvelope()).getCentroid();
					}
					// Distance from p to center along a curve
					double distance = 0;
					if (poiPoint != null) {
						GlobalCoordinates gc1 = new GlobalCoordinates(p.getY(), p.getX());
						GlobalCoordinates gc2 = new GlobalCoordinates(poiPoint.getY(), poiPoint.getX());
						GeodeticCurve curve = gc.calculateGeodeticCurve(Ellipsoid.WGS84, gc1, gc2);
						distance = curve.getEllipsoidalDistance();
						distance = uc.convertUnits(distance, com.esri.arcgis.system.esriUnits.esriMeters, com.esri.arcgis.system.esriUnits.esriMiles);
					}

					sb.append(feature.getValue(12).toString());
					sb.append("\t");
					if (Double.parseDouble(feature.getValue(15).toString()) == 9999) {
						// Fill in "holes"
						IArray ia = elevationLayer.identify(p);
						if (ia.getCount() > 0) {
							IRasterIdentifyObjProxy riop = new IRasterIdentifyObjProxy(ia.getElement(0));

							sb.append(new DecimalFormat("#0.00").format(uc.convertUnits(Double.parseDouble(riop.getName()), com.esri.arcgis.system.esriUnits.esriMeters, com.esri.arcgis.system.esriUnits.esriFeet)) + "ft");
						}
					} else {
						sb.append(feature.getValue(15).toString() + "ft");
					}
					sb.append("\t");
					sb.append(new DecimalFormat("#0.00").format(distance) + "mi");
					sb.append("\t");
					sb.append(coopLayer.getName());
					sb.append("\t");
					sb.append(feature.getValue(0).toString());
					sb.append("\n");
				}
			}
			if (asosLayer != null && asosLayer.isVisible()) {
				IAnnotateLayerProperties alp = this.getALP(asosLayer);
				sf.setWhereClause(alp == null ? "" : alp.getWhereClause());

				IFeatureCursor ifc = asosLayer.search(sf, true);
				for (IFeature feature = ifc.nextFeature(); feature != null; feature = ifc.nextFeature()) {
					Point p = (Point) feature.getShape();
					// Distance from p to center along a curve
					double distance = 0;
					if (poiPoint != null) {
						GlobalCoordinates gc1 = new GlobalCoordinates(p.getY(), p.getX());
						GlobalCoordinates gc2 = new GlobalCoordinates(poiPoint.getY(), poiPoint.getX());
						GeodeticCurve curve = gc.calculateGeodeticCurve(Ellipsoid.WGS84, gc1, gc2);
						distance = curve.getEllipsoidalDistance();
						distance = uc.convertUnits(distance, com.esri.arcgis.system.esriUnits.esriMeters, com.esri.arcgis.system.esriUnits.esriMiles);
					}

					sb.append(feature.getValue(5).toString());
					sb.append("\t");
					sb.append(feature.getValue(11).toString() + "ft");
					sb.append("\t");
					sb.append(new DecimalFormat("#0.00").format(distance) + "mi");
					sb.append("\t");
					sb.append(asosLayer.getName());
					sb.append("\t");
					sb.append(feature.getValue(0).toString());
					sb.append("\n");
				}
			}
			if (awosLayer != null && awosLayer.isVisible()) {
				IAnnotateLayerProperties alp = this.getALP(awosLayer);
				sf.setWhereClause(alp == null ? "" : alp.getWhereClause());

				IFeatureCursor ifc = awosLayer.search(sf, true);
				for (IFeature feature = ifc.nextFeature(); feature != null; feature = ifc.nextFeature()) {
					Point p = (Point) feature.getShape();
					// Distance from p to center along a curve
					double distance = 0;
					if (poiPoint != null) {
						GlobalCoordinates gc1 = new GlobalCoordinates(p.getY(), p.getX());
						GlobalCoordinates gc2 = new GlobalCoordinates(poiPoint.getY(), poiPoint.getX());
						GeodeticCurve curve = gc.calculateGeodeticCurve(Ellipsoid.WGS84, gc1, gc2);
						distance = curve.getEllipsoidalDistance();
						distance = uc.convertUnits(distance, com.esri.arcgis.system.esriUnits.esriMeters, com.esri.arcgis.system.esriUnits.esriMiles);
					}

					sb.append(feature.getValue(3).toString());
					sb.append("\t");
					sb.append(feature.getValue(4)==null ? "Unknown" : feature.getValue(4).toString() + "ft");
					sb.append("\t");
					sb.append(new DecimalFormat("#0.00").format(distance) + "mi");
					sb.append("\t");
					sb.append(awosLayer.getName());
					sb.append("\t");
					sb.append(feature.getValue(0).toString());
					sb.append("\n");
				}
			}
			if (crnLayer != null && crnLayer.isVisible()) {
				IAnnotateLayerProperties alp = this.getALP(crnLayer);
				sf.setWhereClause(alp == null ? "" : alp.getWhereClause());

				IFeatureCursor ifc = crnLayer.search(sf, true);
				for (IFeature feature = ifc.nextFeature(); feature != null; feature = ifc.nextFeature()) {
					Point p = (Point) feature.getShape();
					// Distance from p to center along a curve
					double distance = 0;
					if (poiPoint != null) {
						GlobalCoordinates gc1 = new GlobalCoordinates(p.getY(), p.getX());
						GlobalCoordinates gc2 = new GlobalCoordinates(poiPoint.getY(), poiPoint.getX());
						GeodeticCurve curve = gc.calculateGeodeticCurve(Ellipsoid.WGS84, gc1, gc2);
						distance = curve.getEllipsoidalDistance();
						distance = uc.convertUnits(distance, com.esri.arcgis.system.esriUnits.esriMeters, com.esri.arcgis.system.esriUnits.esriMiles);
					}

					sb.append(feature.getValue(2).toString());
					sb.append("\t");
					sb.append(feature.getValue(3).toString() + "ft");
					sb.append("\t");
					sb.append(new DecimalFormat("#0.00").format(distance) + "mi");
					sb.append("\t");
					sb.append(crnLayer.getName());
					sb.append("\t");
					sb.append(feature.getValue(0).toString());
					sb.append("\n");
				}
			}
			if (nwsLayer != null && nwsLayer.isVisible()) {
				IAnnotateLayerProperties alp = this.getALP(nwsLayer);
				sf.setWhereClause(alp == null ? "" : alp.getWhereClause());

				IFeatureCursor ifc = nwsLayer.search(sf, true);
				for (IFeature feature = ifc.nextFeature(); feature != null; feature = ifc.nextFeature()) {
					Point p = (Point) feature.getShape();
					// Distance from p to center along a curve
					double distance = 0;
					if (poiPoint != null) {
						GlobalCoordinates gc1 = new GlobalCoordinates(p.getY(), p.getX());
						GlobalCoordinates gc2 = new GlobalCoordinates(poiPoint.getY(), poiPoint.getX());
						GeodeticCurve curve = gc.calculateGeodeticCurve(Ellipsoid.WGS84, gc1, gc2);
						distance = curve.getEllipsoidalDistance();
						distance = uc.convertUnits(distance, com.esri.arcgis.system.esriUnits.esriMeters, com.esri.arcgis.system.esriUnits.esriMiles);
					}

					// TODO NWS fields
					sb.append(new DecimalFormat("#0.00").format(distance) + "mi");
					sb.append("\t");
					sb.append(nwsLayer.getName());
					sb.append("\t");
					sb.append(feature.getValue(0).toString());
					sb.append("\n");
				}
			}
			if (spottersLayer != null && spottersLayer.isVisible()) {
				IFeatureCursor ifc = spottersLayer.search(new QueryFilter(), true);
				for (IFeature feature = ifc.nextFeature(); feature != null; feature = ifc.nextFeature()) {
					Point p = (Point) feature.getShape();
					// Distance from p to center along a curve
					double distance = 0;
					if (poiPoint != null) {
						GlobalCoordinates gc1 = new GlobalCoordinates(p.getY(), p.getX());
						GlobalCoordinates gc2 = new GlobalCoordinates(poiPoint.getY(), poiPoint.getX());
						GeodeticCurve curve = gc.calculateGeodeticCurve(Ellipsoid.WGS84, gc1, gc2);
						distance = curve.getEllipsoidalDistance();
						distance = uc.convertUnits(distance, com.esri.arcgis.system.esriUnits.esriMeters, com.esri.arcgis.system.esriUnits.esriMiles);
					}

					sb.append(feature.getValue(2).toString());
					sb.append("\t");
					// derive elevation
					final IArray ia = elevationLayer.identify(feature.getShape());
					if (ia != null && ia.getCount() > 0) {
						final IRasterIdentifyObjProxy riop = new IRasterIdentifyObjProxy(ia.getElement(0));
						sb.append(new DecimalFormat("#0.00").format(uc.convertUnits(Double.parseDouble(riop.getName()), com.esri.arcgis.system.esriUnits.esriMeters, com.esri.arcgis.system.esriUnits.esriFeet)) + "ft");
					}
					sb.append("\t");
					sb.append(new DecimalFormat("#0.00").format(distance) + "mi");
					sb.append("\t");
					sb.append(spottersLayer.getName());
					sb.append("\t");
					sb.append(feature.getValue(0).toString());
					sb.append("\n");
				}
			}

			FileUtils.writeStringToFile(new File(outputFileName),sb.toString(),StandardCharsets.UTF_8);
			return outputFileName;
		} catch (IOException ioe) {
			ioe.printStackTrace();
			return null;
		} finally {
			close(m);
			close(md);
		}
	}
	
	/**
	 * Creates the image output to the output directory provided that a map
	 * document already exists.
	 * 
	 * @param id
	 * @return true iff the image was created successfully
	 */
	private String createImage(File mapFile, String outputFileName) {
		try {
			final RouteExporter e = new RouteExporter();
			e.setSource(mapFile);
			e.setDestination(new File(outputFileName));
			e.setDpi((short) 100);
			e.export();
			return outputFileName;
		} catch (IOException ioe) {
			ioe.printStackTrace();
			return null;
		}
	}

	/**
	 * Construct a WHERE clause which selects only NOS stations which were
	 * active on the argument date.
	 * 
	 * @param date
	 * @return WHERE clause
	 */
	private String getWhereClauseNOS(long date) {
		String dateString = String.valueOf(date);
		dateString = dateString.substring(0, 4) + "-" + dateString.substring(4, 6) + "-" + dateString.substring(6, 8);
		dateString += " 00:00:00";
		final String clause = "\"Install_Date\" <= date '" + dateString + "' AND (\"Removal_Date\" IS NULL OR \"Removal_Date\" >= date '" + dateString + "')";
		return clause;
	}

	/**
	 * Constructs a WHERE clause which selects COOP stations active at the
	 * argument date. The Co-Op station dates in the DB are highly unreliable,
	 * so there is a 20 year buffer applied.
	 * 
	 * @param date
	 * @return WHERE clause
	 */
	private String getWhereClauseCOOP(long date) {
		String dateString = String.valueOf(date);
		String clause = "\"BEGINS\" <= " + dateString + " AND ABS(\"ENDS\") >= " + String.valueOf(date - 100000);

		return clause;
	}

	/**
	 * Constructs a WHERE clause which selects ASOS stations active at the
	 * argument date.
	 * 
	 * @param date
	 * @return WHERE clause
	 */
	private String getWhereClauseASOS(long date) {
		String dateString = String.valueOf(date);
		String clause = "\"COMMISH_DATE\" <= " + dateString;
		return clause;
	}

	/**
	 * Constructs a WHERE clause which selects CRN stations active at the
	 * argument date.
	 * 
	 * @param date
	 * @return WHERE clause
	 */
	private String getWhereClauseCRN(long date) {
		String dateString = String.valueOf(date);
		String clause = "\"StartDate\" <= " + dateString;
		return clause;
	}

	/**
	 * Constructs a WHERE clause which selects NWS stations active at the
	 * argument date.
	 * 
	 * @param date
	 * @return WHERE clause
	 */
	private String getWhereClauseNWS(long date) {
		// TODO
		return "";
	}

	/**
	 * Retrieve the first Annotation Properties from a layer.
	 * 
	 * @param layer
	 * @return Properties or null
	 * @throws IOException
	 */
	private IAnnotateLayerProperties getALP(FeatureLayer layer) throws IOException {
		AnnotateLayerPropertiesCollection alpc = (AnnotateLayerPropertiesCollection) layer.getAnnotationProperties();
		if (alpc.getCount() > 0) {
			int[] alpId = new int[1];
			IAnnotateLayerProperties[] alp = new IAnnotateLayerProperties[1];
			alpc.queryItem(0, alp, alpId);
			return alp[0];
		} else {
			return null;
		}
	}
	
	/**
	 * Returns the total number of rows in a layer.
	 * 
	 * @param id
	 * @param layerName
	 * @return total row count in layer
	 */
	public int getRowCount(String stationsMapFile, LayerType layerType) {
		MapDocument md = null;
		Map m = null;
		FeatureLayer layer = null;
		try {
			// Open Map
			md = new MapDocument();
			md.open(new File(stationsMapFile).getPath(), MAP_PASS);
			m = (Map) md.getMap(0);

			// Get our layer of interest
			
			for (int i = 0; i<m.getLayerCount() && layer==null; i++) {
				final ILayer l = m.getLayer(i);
				if (l instanceof FeatureLayer) {
					if (layerType.name().equalsIgnoreCase(l.getName())) {
						layer = (FeatureLayer) l;
					} else {
						((FeatureLayer) l).release();
					}
				}
			}

			if(layer != null) {
				final int count = layer.rowCount(new QueryFilter());
				return count;
			}
		} catch (IOException ioe) {
			ioe.printStackTrace();
		} finally {
			close(layer);
			close(m);
			close(md);
		}
		return -1;
	}

	/**
	 * Return the field names.
	 * 
	 * @param id
	 * @param layerName
	 * @return array containing field names
	 */
	public List<String> getHeaders(String stationsMapFile, LayerType layerType) {
		MapDocument md = null;
		Map m = null;
		FeatureLayer layer = null;
		try {
			// Open Map
			md = new MapDocument();
			md.open(new File(stationsMapFile).getPath(), MAP_PASS);
			m = (Map) md.getMap(0);

			// Get our layer of interest
			
			for (int i = 0; i < m.getLayerCount(); i++) {
				ILayer l = m.getLayer(i);
				if (l instanceof FeatureLayer) {
					if (layerType.name().equalsIgnoreCase(l.getName())) {
						layer = (FeatureLayer) l;
					}
				}
			}
			
			final List<String> fieldNames = new ArrayList<String>();
			for (int i = 0; i < layer.getFieldCount(); i++) {
				if(!"Shape".equals(layer.getField(i).getName())) {
					fieldNames.add(layer.getField(i).getName());
				}
			}
			
			// Add Layer specific fields
			if(LayerType.ASOS.name().equals(layer.getName())) {
				fieldNames.add("LAT");
				fieldNames.add("LAT_MIN");
				fieldNames.add("LAT_SEC");
				fieldNames.add("LAT_DIR");
				fieldNames.add("LON");
				fieldNames.add("LON_MIN");
				fieldNames.add("LON_SEC");
				fieldNames.add("LON_DIR");
				fieldNames.add("LAT_DEC");
				fieldNames.add("LON_DEC");
			} else if(LayerType.AWOS.name().equals(layer.getName())) {
				fieldNames.add("LAT_DEC");
				fieldNames.add("LON_DEC");
			} else if(LayerType.COOP.name().equals(layer.getName())) {
				fieldNames.add("LAT");
				fieldNames.add("LAT_MIN");
				fieldNames.add("LAT_SEC");
				fieldNames.add("LON");
				fieldNames.add("LON_MIN");
				fieldNames.add("LON_SEC");
				fieldNames.add("LAT_DEC");
				fieldNames.add("LON_DEC");
			} else if(LayerType.CRN.name().equals(layer.getName())) {
				fieldNames.add("LAT_DEC");
				fieldNames.add("LON_DEC");
			} else if(LayerType.NOS.name().equals(layer.getName())) {
				fieldNames.add("LAT");
				fieldNames.add("LAT_MIN");
				fieldNames.add("LAT_DIR");
				fieldNames.add("LON");
				fieldNames.add("LON_MIN");
				fieldNames.add("LON_DIR");
				fieldNames.add("LAT_DEC");
				fieldNames.add("LON_DEC");
			}

			return fieldNames;
		} catch (IOException ioe) {
			ioe.printStackTrace();
			return null;
		} finally {
			close(layer);
			close(m);
			close(md);
		}
	}

	private static String getLatitudeDirection(double latitude) {
		return latitude >= 0 ? "N" : "S"; 
	}
	private static String getLongitudeDirection(double longitude) {
		return longitude >= 0 ? "E" : "W"; 
	}
	private static int getCoordinateHour(double input) {
		return input >= 0 ? (int)Math.floor(input) : (int)Math.ceil(input);
	}
	private static int getCoordinateMinute(double input, int hour) {
		return (int)Math.floor(60*(Math.abs(input) - Math.abs(hour)));
	}
	private static double getCoordinateMinuteDecimal(double input, int hour) {
		return Math.round(600.0*(Math.abs(input) - Math.abs(hour)))/10.0;
	}
	private static int getCoordinateSecond(double input, int hour, int minute) {
		return (int)Math.round(3600*(Math.abs(input) - Math.abs(hour) - (minute/60.0)));
	}
	
	/**
	 * Return a two dimensional array with each row of the selected data set.
	 * 
	 * @param id
	 * @param layerName
	 * @param start
	 * @param count
	 * @return array in which each row corresponds to a row in the feature class
	 */
	public List<List<String>> getRows(final String stationsMapFile, LayerType layerType, int start, int count) {
		MapDocument md = null;
		Map m = null;
		try {
			final NumberFormat coordinateFormatter = new DecimalFormat("#.######");
			
			// Open Map
			md = new MapDocument();
			md.open(new File(stationsMapFile).getPath(), MAP_PASS);
			m = (Map) md.getMap(0);

			// Get our layer of interest
			FeatureLayer layer = null;

			for (int i = 0; i < m.getLayerCount(); i++) {
				ILayer l = m.getLayer(i);
				if (l instanceof FeatureLayer) {
					if (layerType.name().equalsIgnoreCase(l.getName())) {
						layer = (FeatureLayer) l;
					}
				}
			}

			// Apparently queries do not support LIMIT syntax, we cheat here
			// by assuming that the OBJECTID is continuous starting at one
			QueryFilter qf = new QueryFilter();
			qf.setWhereClause("OBJECTID >= " + start + " AND OBJECTID < " + (start + count));

			IFeatureCursor ifc = layer.search(qf, true);
			final List<List<String>> rows = new ArrayList<List<String>>();
			for (IFeature feature = ifc.nextFeature(); feature != null; feature = ifc.nextFeature()) {
				final List<String> row = new ArrayList<String>();
				Point point = null;
				Polygon polygon = null;
				for (int i = 0; i < feature.getFields().getFieldCount(); i++) {
					if("Shape".equalsIgnoreCase(layer.getField(i).getName())) {
						if(feature.getValue(i) instanceof Point) {
							point = (Point)feature.getValue(i);
						} else if(feature.getValue(i) instanceof Polygon) {
							polygon = (Polygon)feature.getValue(i);
						}
					} else {
						row.add(String.valueOf(feature.getValue(i)));
					}
				}
				
				// Add Layer specific fields
				if(point != null && LayerType.ASOS.name().equalsIgnoreCase(layer.getName())) {
					final double latitude = point.getY();
					final double longitude = point.getX();
					final int latitudeHour = getCoordinateHour(latitude);
					final int latitudeMinute = getCoordinateMinute(latitude,latitudeHour);
					final int longitudeHour = getCoordinateHour(longitude);
					final int longitudeMinute = getCoordinateMinute(longitude,longitudeHour);
					row.add(String.valueOf(latitudeHour));
					row.add(String.valueOf(latitudeMinute));
					row.add(String.valueOf(getCoordinateSecond(latitude,latitudeHour,latitudeMinute)));
					row.add(getLatitudeDirection(latitude));
					row.add(String.valueOf(longitudeHour));
					row.add(String.valueOf(longitudeMinute));
					row.add(String.valueOf(getCoordinateSecond(longitude,longitudeHour,longitudeMinute)));
					row.add(getLongitudeDirection(longitude));
					row.add(coordinateFormatter.format(latitude));
					row.add(coordinateFormatter.format(longitude));
				} else if(point != null && LayerType.AWOS.name().equalsIgnoreCase(layer.getName())) {
					final double latitude = point.getY();
					final double longitude = point.getX();
					row.add(coordinateFormatter.format(latitude));
					row.add(coordinateFormatter.format(longitude));
				} else if(point != null && LayerType.CRN.name().equalsIgnoreCase(layer.getName())) {
					final double latitude = point.getY();
					final double longitude = point.getX();
					row.add(coordinateFormatter.format(latitude));
					row.add(coordinateFormatter.format(longitude));
				} else if(point != null && LayerType.NOS.name().equalsIgnoreCase(layer.getName())) {
					final double latitude = point.getY();
					final double longitude = point.getX();
					final int latitudeHour = getCoordinateHour(latitude);
					final int longitudeHour = getCoordinateHour(longitude);
					row.add(String.valueOf(latitudeHour));
					row.add(String.valueOf(getCoordinateMinuteDecimal(latitude,latitudeHour)));
					row.add(getLatitudeDirection(latitude));
					row.add(String.valueOf(longitudeHour));
					row.add(String.valueOf(getCoordinateMinuteDecimal(longitude,longitudeHour)));
					row.add(getLongitudeDirection(longitude));
					row.add(coordinateFormatter.format(latitude));
					row.add(coordinateFormatter.format(longitude));
				} else if(polygon != null && LayerType.COOP.name().equalsIgnoreCase(layer.getName())) {
					final Point pointFrom = (Point)polygon.getFromPoint();
					final Point pointOther = (Point)polygon.getPoint(9);
					final double latitude = pointOther.getY();
					final double longitude = pointFrom.getX();
					final int latitudeHour = getCoordinateHour(latitude);
					final int latitudeMinute = getCoordinateMinute(latitude,latitudeHour);
					final int longitudeHour = getCoordinateHour(longitude);
					final int longitudeMinute = getCoordinateMinute(longitude,longitudeHour);
					row.add(String.valueOf(latitudeHour));
					row.add(String.valueOf(latitudeMinute));
					row.add(String.valueOf(getCoordinateSecond(latitude,latitudeHour,latitudeMinute)));
					row.add(String.valueOf(longitudeHour));
					row.add(String.valueOf(longitudeMinute));
					row.add(String.valueOf(getCoordinateSecond(longitude,longitudeHour,longitudeMinute)));
					row.add(coordinateFormatter.format(latitude));
					row.add(coordinateFormatter.format(longitude));
				}
				
				rows.add(row);
			}
			
			return rows;
		} catch (IOException ioe) {
			ioe.printStackTrace();
			return null;
		} finally {
			close(m);
			close(md);
		}
	}

}
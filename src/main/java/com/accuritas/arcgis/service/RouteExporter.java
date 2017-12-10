package com.accuritas.arcgis.service;

import static com.accuritas.arcgis.util.Tools.close;
import static com.accuritas.arcgis.util.Tools.union;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;

import com.accuritas.arcgis.api.arcfleet.ImageType;
import com.esri.arcgis.carto.FeatureLayer;
import com.esri.arcgis.carto.IActiveView;
import com.esri.arcgis.carto.IElement;
import com.esri.arcgis.carto.ILayer;
import com.esri.arcgis.carto.Map;
import com.esri.arcgis.carto.MapDocument;
import com.esri.arcgis.carto.PageLayout;
import com.esri.arcgis.carto.TextElement;
import com.esri.arcgis.display.DisplayTransformation;
import com.esri.arcgis.geodatabase.Feature;
import com.esri.arcgis.geodatabase.QueryFilter;
import com.esri.arcgis.geometry.Envelope;
import com.esri.arcgis.geometry.ISpatialReference;
import com.esri.arcgis.geometry.Point;
import com.esri.arcgis.geometry.Polyline;
import com.esri.arcgis.geometry.ProjectedCoordinateSystem;
import com.esri.arcgis.output.ExportAI;
import com.esri.arcgis.output.ExportBMP;
import com.esri.arcgis.output.ExportEMF;
import com.esri.arcgis.output.ExportGIF;
import com.esri.arcgis.output.ExportJPEG;
import com.esri.arcgis.output.ExportPDF;
import com.esri.arcgis.output.ExportPNG;
import com.esri.arcgis.output.ExportPS;
import com.esri.arcgis.output.ExportSVG;
import com.esri.arcgis.output.ExportTIFF;
import com.esri.arcgis.output.IExport;
import com.esri.arcgis.system.tagRECT;

/**
 * Provides the facilities necessary to Export a map file to a generic format.
 * 
 */
public class RouteExporter {
	private short dpi = 300;
	private File source;
	private File destination;
	private String sourcePassword = "pass";
	private ImageType destinationType = ImageType.PDF;
	private String title = "Map Title";
	
	/**
	 * Creates an empty exporter.
	 * 
	 * @throws IOException
	 *         if the Licensed constructor throws an IOException
	 */
	public RouteExporter() throws IOException {
		super();
	}

	protected void prepare(Map m) throws IOException {
		FeatureLayer portsLayer = null;
		FeatureLayer progressLayer = null;
		FeatureLayer projectedLayer = null;
		FeatureLayer waypointsLayer = null;
		Envelope layerExtent = null;
		Envelope mapExtent = null;
		ProjectedCoordinateSystem isrS = null;
		try {
			for (int i = 0; i < m.getLayerCount(); i++) {
				ILayer l = m.getLayer(i);
				if (l instanceof FeatureLayer) {
					if ("Ports".equalsIgnoreCase(l.getName())) {
						portsLayer = (FeatureLayer) l;
					} else if ("Progress".equalsIgnoreCase(l.getName())) {
						progressLayer = (FeatureLayer) l;
					} else if ("Projected".equalsIgnoreCase(l.getName())) {
						projectedLayer = (FeatureLayer) l;
					} else if ("Waypoints".equalsIgnoreCase(l.getName())) {
						waypointsLayer = (FeatureLayer) l;
					}
				}
			}
	
			// Resize based on nominally merged layers
			if (portsLayer != null) {
				layerExtent = union(new FeatureLayer[] { portsLayer, progressLayer, projectedLayer, waypointsLayer });
	
				// Move Meridian of projection to avoid tearing
				ISpatialReference isr = m.getSpatialReference();
				if (isr instanceof ProjectedCoordinateSystem && layerExtent != null) {
					double meridian = this.getMeridian(new FeatureLayer[] { portsLayer, progressLayer, projectedLayer });
					isrS = (ProjectedCoordinateSystem)isr;
					isrS.setCentralMeridian(true, meridian);
				}
	
				// Center of Ports layer
				if(layerExtent != null) {
					close(layerExtent);
				}
				layerExtent = union(new FeatureLayer[] { portsLayer, progressLayer, projectedLayer, waypointsLayer });
				mapExtent = (Envelope) m.getExtent();
	
				if (layerExtent != null) {
					// Recenter Map
					mapExtent.centerAt(layerExtent.getCentroid());
	
					// Resize Map
					double marginRatio = 0.15;
					double marginWidth = layerExtent.getWidth() * marginRatio;
					double marginHeight = layerExtent.getHeight() * marginRatio;
					double ratio;
	
					// Shrink
					if ((mapExtent.getWidth() > (layerExtent.getWidth() + marginWidth)) && (mapExtent.getHeight() > (layerExtent.getHeight() + marginHeight))) {
						ratio = Math.max((layerExtent.getWidth() + marginWidth) / (2 * mapExtent.getWidth()), (layerExtent.getHeight() + marginHeight) / mapExtent.getHeight());
						// System.out.println(ratio);
						mapExtent.scale(layerExtent.getCentroid(), ratio, ratio);
					}
				}
	
				// Fix Extent
				m.setExtent(mapExtent);
				m.refresh();
			}
		} finally {
			close(isrS);
			close(mapExtent);
			close(layerExtent);
			close(portsLayer);
			close(progressLayer);
			close(projectedLayer);
			close(waypointsLayer);
		}
	}

	/**
	 * Attempts to determine the meridian for a set of layers.
	 * 
	 * @param layers
	 * @return The meridian in degrees.
	 * @throws IOException
	 *         if the underlying ESRI methods throw an IOException.
	 */
	public double getMeridian(FeatureLayer[] layers) throws IOException {
		double meridian = 0.0;
		Point point = null;
		Point pointA = null;
		Point pointB = null;
		try {
			if (layers[0] != null) {
				int portCount = layers[0].rowCount(new QueryFilter());
				if (portCount == 1) {
					// If there is only one port, then center on it
					final Feature f = (Feature) layers[0].getRow(1);
					point = ((Point)f.getShape());
					meridian = point.getX();
				}
				if (portCount > 1) {
					// If there is more than one port, then we average
					pointA = ((Point) ((Feature) layers[0].getRow(1)).getShape());
					pointB = ((Point) ((Feature) layers[0].getRow(portCount)).getShape());
					double a = pointA.getX();
					double b = pointB.getX();
	
					boolean dateline = false;
					for (int i = 1; i < layers.length && !dateline; i++) {
						int projectedCount = layers[i].rowCount(new QueryFilter());
						for (int j = 1; j <= projectedCount && !dateline; j++) {
							final Feature f = (Feature) layers[i].getRow(j);
							Polyline pl = (Polyline) f.getShape();
							double from = ((Point) pl.getFromPoint()).getX();
							double to = ((Point) pl.getToPoint()).getX();
							if ((from <= 180 && to >= 180) || (to <= 180 && from >= 180)) {
								dateline = true;
							}
							close(f);
							close(pl);
						}
					}
	
					if (dateline) {
						meridian = ((a + b) / 2.0) - 180;
					} else {
						meridian = (a + b) / 2.0;
					}
				}
			}
		} finally {
			close(point);
			close(pointA);
			close(pointB);
		}
		return meridian;
	}
	
	/**
	 * Exports a map document from the source file to the destination file.
	 * 
	 * @throws IOException
	 *         if an ESRI method throws an IOException
	 */
	public void export() throws IOException {
		MapDocument md = null;
		Map m = null;
		PageLayout pl = null;
		DisplayTransformation plDT = null;
		DisplayTransformation mDT = null;
		Envelope e = null;
		IExport ie = null;
		try {
			// Open map document
			md = new MapDocument();
			md.open(this.getSource().getPath(), this.getSourcePassword());
	
			// Get layers and find centroid
			m = (Map) md.getMap(0);
	
			// Call subclass prepare method to recenter, etc
			this.prepare(m);
			
			final IActiveView av = md.getActiveView();
			if (av != null) {
				av.refresh();
			}
	
			// PageLayout and Exporter
			pl = (PageLayout) md.getPageLayout();
			ie = getExport(this.getDestinationType());
	
			// Give my map a title and update copyright
			pl.reset();
			int numItemsUpdated=0;
			for (IElement n = pl.next(); n != null && numItemsUpdated<2; n = pl.next()) {
				if(n instanceof TextElement) {
					final TextElement te = (TextElement) n;
					if ("Title".equalsIgnoreCase(te.getName())) {
						te.setText(this.getTitle());
						numItemsUpdated++;
					} else if(te.getText().indexOf("All Rights Reserved") != -1) {
						final int currentYear = Calendar.getInstance().get(Calendar.YEAR);
						te.setText("�"+currentYear+" FleetWeather, Inc. All Rights Reserved");
						numItemsUpdated++;
					}
					te.release();
				}
			}
			pl.reset();
			pl.refresh();
	
			// Set output quality for JPEG...
			if (ie instanceof ExportJPEG || ie instanceof ExportGIF) {
				plDT = (DisplayTransformation) pl.getScreenDisplay().getDisplayTransformation();
				plDT.setResampleRatio(1);
				mDT = (DisplayTransformation) m.getScreenDisplay().getDisplayTransformation();
				mDT.setResampleRatio(1);
			}
	
			// Building Envelope
			e = new Envelope();
			double[] dWidth = { 0 }, dHeight = { 0 };
			pl.getPage().querySize(dWidth, dHeight);
			e.putCoords(0, 0, this.getDpi() * dWidth[0], this.getDpi()
							* dHeight[0]);
	
			// Configuring Exporter
			ie.setPixelBounds(e);
			ie.setResolution(this.getDpi());
			ie.setExportFileName(this.getDestination().getPath());
	
			// Building tagRECT
			tagRECT tr = new tagRECT();
			tr.left = (int) ie.getPixelBounds().getLowerLeft().getX();
			tr.bottom = (int) ie.getPixelBounds().getUpperRight().getY();
			tr.right = (int) ie.getPixelBounds().getUpperRight().getX();
			tr.top = (int) ie.getPixelBounds().getLowerLeft().getY();
	
			// Exporting
			int i = ie.startExporting();
			pl.output(i, this.getDpi(), tr, null, null);
		} finally {
			close(ie);
			close(e);
			close(plDT);
			close(mDT);
			close(pl);
			close(m);
			close(md);
		}
	}
	
	private IExport getExport(ImageType type) throws IOException {
		switch(type) {
			case AI : return new ExportAI();
			case BMP : return new ExportBMP();
			case EMF : return new ExportEMF();
			case GIF : return new ExportGIF();
			case JPEG : return new ExportJPEG();
			case PNG : return new ExportPNG();
			case PS : return new ExportPS();
			case SVG : return new ExportSVG();
			case TIFF : return new ExportTIFF();
			default:
			case PDF : return new ExportPDF();
		}
	}

	public short getDpi() {
		return dpi;
	}

	public void setDpi(short dpi) {
		this.dpi = dpi;
	}

	public File getSource() {
		return source;
	}

	public void setSource(File source) {
		this.source = source;
	}

	public File getDestination() {
		return destination;
	}

	public void setDestination(File destination) {
		this.destination = destination;
		final String name = destination.getName();
		if (name.contains(".")) {
			this.destinationType = ImageType.getMatchingType(name.substring(name.lastIndexOf(".")+1, name.length()));
		}
	}

	public String getSourcePassword() {
		return sourcePassword;
	}

	public void setSourcePassword(String sourcePassword) {
		this.sourcePassword = sourcePassword;
	}

	public ImageType getDestinationType() {
		return destinationType;
	}

	public void setDestinationType(ImageType destinationType) {
		this.destinationType = destinationType;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}
}

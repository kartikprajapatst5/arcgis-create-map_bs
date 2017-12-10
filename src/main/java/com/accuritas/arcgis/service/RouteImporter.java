package com.accuritas.arcgis.service;

import static com.accuritas.arcgis.util.Tools.close;
import static com.accuritas.arcgis.util.Tools.createPoint;
import static com.accuritas.arcgis.util.Tools.createPolyline;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;

import com.accuritas.arcgis.api.arcfleet.VoyageDetail;
import com.esri.arcgis.datasourcesGDB.FileGDBWorkspaceFactory;
import com.esri.arcgis.geodatabase.FeatureClass;
import com.esri.arcgis.geodatabase.Workspace;

/**
 * Provides the facilities necessary to Import data into a GeoDatabase from the
 * FleetTrak system.
 */
public class RouteImporter {

	private File destinationGDB;
	private String voyageId;
	private String vesselName;
	private List<VoyageDetail> voyageDetails;

	public String getVoyageId() {
		return voyageId;
	}

	public void setVoyageId(String voyageId) {
		this.voyageId = voyageId;
	}

	public String getVesselName() {
		return vesselName;
	}

	public void setVesselName(String vesselName) {
		this.vesselName = vesselName;
	}

	public List<VoyageDetail> getVoyageDetails() {
		return voyageDetails;
	}

	public void setVoyageDetails(List<VoyageDetail> voyageDetails) {
		this.voyageDetails = voyageDetails;
	}

	/**
	 * Creates an empty Importer.
	 * 
	 * @throws IOException
	 *         if the Licensed constructor throws an IOException
	 */
	public RouteImporter() throws IOException {
		super();
	}
	
	/**
	 * Perform the export.
	 * 
	 * @throws IOException
	 *         if the underlying ESRI functions throw IOException
	 */
	public void doImport() throws IOException {
		// Create copy of GDB
		final File destinationGDB = this.getDestinationGDB();

		// Open destination GDB
		final FileGDBWorkspaceFactory fgdbwf = new FileGDBWorkspaceFactory();
		final Workspace w = new Workspace(fgdbwf.openFromFile(destinationGDB.getPath(), 0));

		// Get the necessary feature classes
		final FeatureClass progress = new FeatureClass(w.openFeatureClass("Progress"));
		final FeatureClass projected = new FeatureClass(w.openFeatureClass("Projected"));
		final FeatureClass ports = new FeatureClass(w.openFeatureClass("Ports"));
		final FeatureClass waypoints = new FeatureClass(w.openFeatureClass("Waypoints"));

		// Add a simple test datum
		w.startEditing(true);
		w.startEditOperation();

		try {
			// Retrieve voyage details
			double[] previous = { 0, 0 };
			String previousType = null;
			boolean cp = false;
			final SimpleDateFormat sdf = new SimpleDateFormat("dd MMM HHmm");
			final SimpleDateFormat sdfA = new SimpleDateFormat("dd/HHmm");
			
			boolean crossPrimeMeridian = false;
			boolean crossInternationalDateLine = false;
			for(int i=1; i<voyageDetails.size(); i++) {
				double x = -voyageDetails.get(i).getLon();
				double previousValue = -voyageDetails.get(i-1).getLon();
				if (previousValue > 0 && x < 0) {
					if (previousValue > 90) {
						crossInternationalDateLine = true;
					} else {
						crossPrimeMeridian = true;
					}
				} else if (previousValue < 0 && x > 0) {
					if (x > 90) {
						crossInternationalDateLine = true;
					} else {
						crossPrimeMeridian = true;
					}
				}
			}
			
			//Collections.reverse(details);
			
			// Parse voyage details
			for(VoyageDetail detail: voyageDetails) {
				// Read data
				final double x = detail.getLon();
				// x = x < 0 ? 360.0 + x : x;
				final double y = detail.getLat();
				final String sdfString = detail.getDate() == null ? "" : sdf.format(detail.getDate())	+ "Z";
				final String sdfAString = detail.getDate() == null ? "" : sdfA.format(detail.getDate()) + "Z";

				// Departure Port
				if ("ETD".equalsIgnoreCase(detail.getType()) || "ATD".equalsIgnoreCase(detail.getType())) {
					final String positionLabel = (detail.getName() != null
							&& detail.getName().length() > 0 ? detail.getName() + "\n"
							: "")
							+ detail.getType() + ": " + sdfString;
					createPoint(ports, x, y, positionLabel);
				}

				// Actual Labels
				if (detail.getType() != null && "A".equalsIgnoreCase(detail.getType())) {
					createPoint(ports, x, y, sdfAString);
				}

				// Have we even left yet?
				if (detail.getId() == 1 && "ETD".equalsIgnoreCase(detail.getType())) {
					cp = true;
				}

				// Intermediate Line segments
				// Skip of we are at the first point, and skip if we are in the
				// gap between a arrival and departure
				if (detail.getId() > 1 && previousType!=null && !(previousType.contains("TA") && detail.getType().contains("TD"))) {
					// We have just become an estimated position
					if (("ETD".equalsIgnoreCase(detail.getType()) || "CP".equalsIgnoreCase(detail.getType()) || "ETA".equalsIgnoreCase(detail.getType()))) {
						cp = true;
					}

					
					// Draw line for progress or projection
					// System.out.println(previous[0] + ", " + x);
					if(crossPrimeMeridian) {
						createPolyline(cp ? projected : progress, previous[0], previous[1], x, y);
					} else if (previous[0] > 0 && x < 0) {
						if (previous[0] > 90) {
							// Rectify values which cross the dateline
							createPolyline(cp ? projected : progress, previous[0], previous[1], 360 + x, y);
						} else {
							// Rectify values which cross the prime meridian
							createPolyline(cp ? projected : progress, 360 + previous[0], previous[1], 360 + x, y);
						}
					} else if (previous[0] < 0 && x > 0) {
						if (x > 90) {
							// Rectify values which cross the dateline
							createPolyline(cp ? projected : progress, 360 + previous[0], previous[1], x, y);
						} else {
							// Rectify values which cross the prime meridian
							createPolyline(cp ? projected : progress, 360 + previous[0], previous[1], 360 + x, y);
						}
					} else if (previous[0] < 0 && x < 0) {
						createPolyline(cp ? projected : progress, 360 + previous[0], previous[1], 360 + x, y);
					} else {
						createPolyline(cp ? projected : progress, previous[0], previous[1], x, y);
					}
				}

				previous[0] = x;
				previous[1] = y;
				previousType = detail.getType();

				// Arrival Port
				if ("ETA".equalsIgnoreCase(detail.getType()) || "ATA".equalsIgnoreCase(detail.getType())) {
					final String positionLabel = (detail.getName() != null
							&& detail.getName().length() > 0 ? detail.getName() + "\n"
							: "")
							+ detail.getType() + ": " + sdfString;
					createPoint(ports, x, y, positionLabel);
				}

				// Waypoints...
				final HashMap<String, Object> waypointValues = new HashMap<String, Object>();
				waypointValues.put("name", vesselName == null ? sdfString : (vesselName + "\n" + sdfString));
				waypointValues.put("vesselName", vesselName == null ? "" : vesselName);
				waypointValues.put("positionType", detail.getType() == null ? "" : detail.getType());
				waypointValues.put("positionDate", sdfString == null ? "" : sdfString);
				createPoint(waypoints, x, y, waypointValues);
			}
			
	
			// Stop Editing
			w.stopEditOperation();
			w.stopEditing(true);
		} finally {
			close(progress);
			close(projected);
			close(waypoints);
			close(ports);
			close(progress);
			close(w);
			close(fgdbwf);
		}

		// Update Extent
		// progress.updateExtent();
		// projected.updateExtent();
		// ports.updateExtent();
	}

	public File getDestinationGDB() {
		return destinationGDB;
	}

	public void setDestinationGDB(File destinationGDB) {
		this.destinationGDB = destinationGDB;
	}

}
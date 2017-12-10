package com.accuritas.arcgis.service;

import java.io.File;
import java.io.IOException;

import com.accuritas.arcgis.api.arcfleet.MapImageRequest;
import com.accuritas.arcgis.api.arcfleet.MapSize;

public class CreateMapService {
	
	/** Class Members **/
	private static final short DPI_LARGE = 100;
	private static final short DPI_SMALL = 96;
	
	public CreateMapService() {
		super();
	}
	
	public void createMapImage(MapImageRequest mapImageRequest, String tempRouteFolderName, String outputFileName) throws IOException, InterruptedException {
		// Setup resuable paths
		final File tempPath = new File(tempRouteFolderName);
		final File tempGDB = new File(tempPath,"Temp.gdb");
		final File tempRouteFile = new File(tempPath,"Routes.mxd");
		
		// Import data
		final RouteImporter importer = new RouteImporter();
		importer.setDestinationGDB(tempGDB);
		importer.setVoyageId(mapImageRequest.getVoyageId());
		importer.setVesselName(mapImageRequest.getVesselName());
		importer.setVoyageDetails(mapImageRequest.getVoyageDetails());
		importer.doImport();
		
		// Export image
		final RouteExporter e = new RouteExporter();
		final String fileName = outputFileName;
		e.setDestination(new File(fileName));
		e.setDestinationType(mapImageRequest.getImageType());
		if(mapImageRequest.getMapSize().equals(MapSize.LARGE)) {
			e.setTitle(mapImageRequest.getTitle());
			e.setDpi(DPI_LARGE);
		} else {
			e.setDpi(DPI_SMALL);
		}
		e.setSource(tempRouteFile);
		e.export();
	}

}
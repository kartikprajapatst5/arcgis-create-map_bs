package com.accuritas.arcgis.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.io.FileUtils;

import com.accuritas.arcgis.message.ArcGisMessageType;
import com.accuritas.arcgis.message.CreateForensicMapMessage;
import com.accuritas.arcgis.message.CreateForensicStationMessage;
import com.accuritas.arcgis.message.CreateVoyageMapMessage;
import com.esri.arcgis.interop.AutomationException;
import com.esri.arcgis.system.AoInitialize;
import com.esri.arcgis.system.EngineInitializer;
import com.esri.arcgis.system.esriLicenseProductCode;
import com.google.gson.Gson;

public class MainService {

	/** Class Members **/
	private static AoInitialize aoi;
	
	public static void main(String[] args) throws AutomationException, IOException, InterruptedException {
		if(args.length<2) {
			throw new IllegalArgumentException("Missing voyage id");
		}
		final ArcGisMessageType type = ArcGisMessageType.valueOf(args[0]);
		final String messageFile = args[1];
		final Gson gson = new Gson();
		
		try (BufferedReader r = new BufferedReader(new FileReader(messageFile))) {
			switch(type) {
				case VOYAGE_MAP:
					final CreateVoyageMapMessage messageVoyageMap = gson.fromJson(r, CreateVoyageMapMessage.class);
					startupLicense(messageVoyageMap.isUseRuntimeEngine());
					new CreateMapService().createMapImage(messageVoyageMap.getMapImageRequest(),messageVoyageMap.getTempRouteFolderName(),messageVoyageMap.getOutputFileName());
					break;
				case FORENSIC_MAP:
					final CreateForensicMapMessage messageForensicMap = gson.fromJson(r, CreateForensicMapMessage.class);
					startupLicense(messageForensicMap.isUseRuntimeEngine());
					new ForensicService().createMapFile(messageForensicMap.getCreateMapRequest(),messageForensicMap.getMapFileName(),messageForensicMap.getCustomGDBName(),messageForensicMap.getOutputFileName());
					break;
					
				case FORENSIC_COUNT:
					final CreateForensicStationMessage messageForensicStationCount = gson.fromJson(r, CreateForensicStationMessage.class);
					startupLicense(messageForensicStationCount.isUseRuntimeEngine());
					final int count = new ForensicService().getRowCount(messageForensicStationCount.getStationsMapFile(), messageForensicStationCount.getLayerType());
					final String dataCount = String.valueOf(count);
					FileUtils.writeStringToFile(new File(messageForensicStationCount.getOutputFileName()),dataCount,StandardCharsets.UTF_8);
					break;
					
				case FORENSIC_HEADERS:
					final CreateForensicStationMessage messageForensicStationHeaders = gson.fromJson(r, CreateForensicStationMessage.class);
					startupLicense(messageForensicStationHeaders.isUseRuntimeEngine());
					final List<String> headers = new ForensicService().getHeaders(messageForensicStationHeaders.getStationsMapFile(), messageForensicStationHeaders.getLayerType());
					final String dataHeaders = gson.toJson(headers);
					FileUtils.writeStringToFile(new File(messageForensicStationHeaders.getOutputFileName()),dataHeaders,StandardCharsets.UTF_8);
					break;
					
				case FORENSIC_ROWS:
					final CreateForensicStationMessage messageForensicStationRows = gson.fromJson(r, CreateForensicStationMessage.class);
					startupLicense(messageForensicStationRows.isUseRuntimeEngine());
					final List<List<String>> rows = new ForensicService().getRows(messageForensicStationRows.getStationsMapFile(), messageForensicStationRows.getLayerType(), messageForensicStationRows.getStart(), messageForensicStationRows.getCount());
					final String dataRows = gson.toJson(rows);
					FileUtils.writeStringToFile(new File(messageForensicStationRows.getOutputFileName()),dataRows,StandardCharsets.UTF_8);
					break;
			}
		} finally {
			shutdownLicense();
		}
	}
	private static void startupLicense(boolean useRuntimeEngine) throws IOException {
		EngineInitializer.initializeEngine();
        try {
            aoi = new AoInitialize();
            if(useRuntimeEngine) {
                aoi.initialize(esriLicenseProductCode.esriLicenseProductCodeEngine);
            } else {
                aoi.initialize(esriLicenseProductCode.esriLicenseProductCodeBasic);
            }
        } catch (IOException ioe) {
            throw ioe;
        }
	}
	
	private static void shutdownLicense() throws AutomationException, IOException {
		if(aoi != null) {
			aoi.shutdown();
			aoi = null;
		}
	}

}
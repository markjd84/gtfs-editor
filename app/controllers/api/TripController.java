package controllers.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;

import models.transit.AttributeAvailabilityType;
import models.transit.ServiceCalendar;
import models.transit.StopTime;
import models.transit.Route;
import models.transit.Trip;
import models.transit.TripPattern;
import models.transit.TripPatternStop;
import models.transit.TripDirection;
import controllers.Base;
import controllers.Application;
import controllers.Secure;
import controllers.Security;
import datastore.VersionedDataStore;
import datastore.AgencyTx;
import play.Logger;
import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.With;

@With(Secure.class)
public class TripController extends Controller {
	@Before
	static void initSession() throws Throwable {
		 
		if(!Security.isConnected() && !Application.checkOAuth(request, session))
			Secure.login();
	}
	
    public static void getTrip(String id, String patternId, String calendarId, String agencyId) {
    	if (agencyId == null)
    		agencyId = session.get("agencyId");
    	
    	if (agencyId == null) {
    		badRequest();
    		return;
    	}
    	
    	AgencyTx tx = VersionedDataStore.getAgencyTx(agencyId);
    	
        try {
            if (id != null) {
            	if (tx.trips.containsKey(id))
            		renderJSON(Base.toJson(tx.trips.get(id), false));
            	else
            		notFound();
            }
            else if (patternId != null && calendarId != null) {
            	if (!tx.tripPatterns.containsKey(patternId) || !tx.calendars.containsKey(calendarId)) {
            		notFound();
            	}
            	else {
            		renderJSON(Base.toJson(tx.getTripsByPatternAndCalendar(patternId, calendarId), false));
            	}
            }

            else if(patternId != null) {
            	renderJSON(Base.toJson(tx.getTripsByPattern(patternId), false));
            }
            else {
            	renderJSON(Base.toJson(tx.trips.values(), false));
            }
                
        } catch (Exception e) {
        	tx.rollback();
            e.printStackTrace();
            badRequest();
        }

    }
    
    public static void createTrip() {
    	AgencyTx tx = null;
    	
        try {
        	Trip trip = Base.mapper.readValue(params.get("body"), Trip.class);
        	
        	Logger.info("Attempting to create trip for agency " + trip.agencyId + " from session by agency " + session.get("agencyId"));
        	
            if (session.contains("agencyId") && !session.get("agencyId").equals(trip.agencyId))
            	badRequest();
        	
        	if (!VersionedDataStore.agencyExists(trip.agencyId)) {
        		badRequest();
        		return;
        	}  	
        	
        	tx = VersionedDataStore.getAgencyTx(trip.agencyId);
        	
        	if (tx.trips.containsKey(trip.id)) {
        		tx.rollback();
        		badRequest();
        		return;
        	}
        	
        	boolean validPatId = tx.tripPatterns.containsKey(trip.patternId);
        	Logger.info("Valid pattern id:" + validPatId);
        	
        	// TODO: check timetable-based trips are not broken by the slightly adjusted API submission
        	// (should be fine as nothing has been removed and non-required data is ignored)        	
        	if (trip.useFrequency) {
        		// Retrieve pattern stops and recast
        		ArrayList<StopTime> patStops = tx.tripPatterns.get(trip.patternId).patternStopsAsStopTimes();        		
        		trip.stopTimes = patStops;       
        	}
        	else {
        		Logger.info("Not using frequencies for trip " + trip.id);
        	}
        	
        	// Check a GTFS ID has been set (or set one)
        	if (trip.gtfsTripId == null) {
        		trip.gtfsTripId = "TRIP_" + trip.id;
        	}
        	
        	// in case we need a default
        	ServiceCalendar def = null;
        	
        	try {
        		// Pull all the info required from the associated route
            	if (validPatId) {
            		Route route = tx.routes.get(tx.tripPatterns.get(trip.patternId).routeId);
            		trip.routeId = route.id;
            		trip.tripShortName = route.routeShortName;
            		trip.tripHeadsign = tx.tripPatterns.get(trip.patternId).headsign;
            	}

            	if (trip.tripDirection == null)
            		// This needs to be retrieved from the UI somehow
            		trip.tripDirection = TripDirection.A;
            	
            	if (trip.blockId == null)
            		trip.blockId = "";
            	
            	
            	/*
            	 * How did this work before? Is it that Postgres didn't insist on all fields?
            	 * 
            	 * At the moment, just creating a blank 'default' calendar for each trip
            	 * This is then overwritten when a user provides better info
            	 */
            	if ((trip.calendarId == null) || (trip.calendarId.equals(""))) {
        			Logger.warn("Did not obtain a usable calendar ID from UI");
            		def = new ServiceCalendar(trip.agencyId, "", "Default calendar");
            		trip.calendarId = def.id;
            	}
            	
            	if (trip.wheelchairBoarding == null)
            		trip.wheelchairBoarding = AttributeAvailabilityType.UNKNOWN;
            	
            	if (trip.invalid == null)
            		trip.invalid = false;
            	
            	int newTripStopCount = trip.stopTimes.size();
            	int passedTripStopCount = tx.tripPatterns.get(trip.patternId).patternStops.size();
            	        	
            	if (!validPatId || newTripStopCount != passedTripStopCount) {
            		tx.rollback();
            		badRequest();
            		return;
            	}
            	
        	} catch (NullPointerException e) {
        		e.printStackTrace();
                if (tx != null) tx.rollbackIfOpen();
                Logger.error("Failed to populate trip data!");
                badRequest();
        	}
        	      	        	
        	tx.trips.put(trip.id, trip);
        	
        	if (def != null) {
        		tx.calendars.put(def.id, def);
        	}
        	tx.commit();
        	Logger.info("Successfully saved trip id " + trip.id + " for agency " + trip.agencyId);  
        	renderJSON(Base.toJson(trip, false));
        } catch (Exception e) {
        	
            e.printStackTrace();
            if (tx != null) tx.rollbackIfOpen();
            badRequest();
        }
    }
    
    public static void updateTrip() {
    	AgencyTx tx = null;
    	
        try {
        	Trip trip = Base.mapper.readValue(params.get("body"), Trip.class);
        	        	
        	if (session.contains("agencyId") && !session.get("agencyId").equals(trip.agencyId))
            	badRequest();
        	
        	if (!VersionedDataStore.agencyExists(trip.agencyId)) {
        		badRequest();
        		return;
        	}
        	
        	tx = VersionedDataStore.getAgencyTx(trip.agencyId);
        	
        	// Reusing the code from the createTrip method above, just to ensure consistency
        	if (trip.useFrequency) {
        		// Retrieve pattern stops and recast
        		ArrayList<StopTime> patStops = tx.tripPatterns.get(trip.patternId).patternStopsAsStopTimes();        		
        		trip.stopTimes = patStops;       
        	}

        	if (!tx.trips.containsKey(trip.id)) {
        		tx.rollback();
        		badRequest();
        		return;
        	}
        	
        	if (!tx.tripPatterns.containsKey(trip.patternId) || trip.stopTimes.size() != tx.tripPatterns.get(trip.patternId).patternStops.size()) {
        		tx.rollback();
        		badRequest();
        		return;
        	}
        	
        	// Disallow trips that have no start or end times, or have no difference between the two
        	if (trip.startTime == null || trip.endTime == null || trip.startTime.equals(trip.endTime)) {
        		tx.rollback();
        		badRequest();
        		return;
        	}
        	
        	TripPattern patt = tx.tripPatterns.get(trip.patternId);
        	
        	// confirm that each stop in the trip matches the stop in the pattern
        	
        	for (int i = 0; i < trip.stopTimes.size(); i++) {
        		TripPatternStop ps = patt.patternStops.get(i);
        		StopTime st =  trip.stopTimes.get(i);
        		
        		if (st == null)
        			// skipped stop
        			continue;
        		
        		if (!st.stopId.equals(ps.stopId)) {
        			Logger.error("Mismatch between stop sequence in trip and pattern at position %s, pattern: %s, stop: %s", i, ps.stopId, st.stopId);
        			tx.rollback();
        			badRequest();
        			return;
        		}
        	}
        	
        	tx.trips.put(trip.id, trip);
        	tx.commit();
        	
        	renderJSON(Base.toJson(trip, false));
        } catch (Exception e) {
        	if (tx != null) tx.rollbackIfOpen();
            e.printStackTrace();
            badRequest();
        }
    }

    public static void deleteTrip(String id, String agencyId) {
    	if (agencyId == null)
    		agencyId = session.get("agencyId");
    	
        if (id == null || agencyId == null) {
            badRequest();
            return;
        }

        AgencyTx tx = VersionedDataStore.getAgencyTx(agencyId);
        Trip trip = tx.trips.remove(id);
        String json;
		try {
			json = Base.toJson(trip, false);
		} catch (IOException e) {
			badRequest();
			return;
		}
        tx.commit();
        
        renderJSON(json);
    }
}

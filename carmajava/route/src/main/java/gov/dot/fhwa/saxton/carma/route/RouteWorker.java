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

package gov.dot.fhwa.saxton.carma.route;

import cav_msgs.*;
import cav_srvs.SetActiveRouteResponse;
import cav_srvs.StartActiveRouteResponse;
import gov.dot.fhwa.saxton.carma.geometry.geodesic.Location;
import gov.dot.fhwa.saxton.carma.rosutils.SaxtonLogger;
import org.apache.commons.logging.Log;
import org.ros.message.MessageFactory;
import org.ros.message.Time;
import org.ros.node.NodeConfiguration;
import sensor_msgs.NavSatFix;
import sensor_msgs.NavSatStatus;
import java.io.File;
import java.io.FilenameFilter;
import java.util.Collection;
import java.util.HashMap;

/**
 * The RouteWorker is responsible for implementing all non pub-sub logic of the RouteManager node
 * The class operates as a state machine with states LOADING_ROUTES, ROUTE_SELECTION, READY_TO_FOLLOW and FOLLOWING_ROUTE
 * The state of the RouteWorker is used by a ros node to determine which topics will be available
 */
public class RouteWorker {
  protected final IRouteManager routeManager;
  protected final SaxtonLogger log;
  protected final NodeConfiguration nodeConfiguration = NodeConfiguration.newPrivate();
  protected final MessageFactory messageFactory = nodeConfiguration.getTopicMessageFactory();
  // State array to assign indexes to states
  protected final WorkerState[] states =
    { WorkerState.LOADING_ROUTES, WorkerState.ROUTE_SELECTION, WorkerState.WAITING_TO_START,
      WorkerState.FOLLOWING_ROUTE };
  // Transition table for state machine
  protected final int[][] transition = {
    /*STATES: LoadingRoutes(0), RouteSelection(1), WaitingToStart(2), FollowingRoute(3) */
    /*          EVENTS           */
    { 1, 1, 2, 3 }, /*FILES_LOADED    */
    { 0, 2, 2, 2 }, /*ROUTE_SELECTED  */
    { 0, 1, 1, 1 }, /*ROUTE_COMPLETED */
    { 0, 1, 1, 1 }, /*LEFT_ROUTE      */
    { 0, 1, 1, 1 }, /*SYSTEM_FAILURE  */
    { 0, 1, 1, 1 }, /*SYSTEM_NOT_READY*/
    { 0, 1, 3, 3 }, /*ROUTE_STARTED   */
    { 0, 1, 1, 1 }, /*ROUTE_ABORTED   */ };

  // Current state
  protected int currentStateIndex = 0;

  protected Route activeRoute;
  protected HashMap<String, Route> availableRoutes = new HashMap<>();
  protected RouteSegment currentSegment;
  protected int currentSegmentIndex = 0;
  protected Location hostVehicleLocation = new Location();

  protected int currentWaypointIndex = 0;
  protected double downtrackDistance = 0;
  protected double crossTrackDistance = 0;
  protected boolean systemOkay = false;
  protected int routeStateSeq = 0;
  protected final int requiredLeftRouteCount;
  protected int recievedLeftRouteEvents = 0;
  protected double currentSegmentDowntrack = 0;

  /**
   * Constructor initializes a route worker object with the provided logging tool
   *
   * @param log The logger to be used
   */
  public RouteWorker(IRouteManager manager, Log log, int requiredLeftRouteCount) {
    this.log = new SaxtonLogger(this.getClass().getSimpleName(), log);
    this.routeManager = manager;
    this.requiredLeftRouteCount = requiredLeftRouteCount;
  }

  /**
   * Constructor initializes the state and transitions and starts the timeout timer for the starting state
   *
   * @param manager negotiation manager which is used to publish data
   * @param log     the logger
   */
  public RouteWorker(IRouteManager manager, Log log, String database_path, int requiredLeftRouteCount) {
    this.routeManager = manager;
    this.log = new SaxtonLogger(this.getClass().getSimpleName(), log);
    this.requiredLeftRouteCount = requiredLeftRouteCount;
    // Load route files from database
    log.info("RouteDatabasePath: " + database_path);
    File folder = new File(database_path);
    File[] listOfFiles = folder.listFiles(new FilenameFilter() {
      public boolean accept(File dir, String filename)
      {
        return filename.endsWith(".yaml") || filename.endsWith(".yml");
      }
    });
    log.info("FolderPathInJava: " + folder.getAbsolutePath());
    if (listOfFiles == null || listOfFiles.length == 0) { // Check if route files exist
      log.warn("No route files found at directory: " + folder.getAbsolutePath());
      return;
    }
    for (int i = 0; i < listOfFiles.length; i++) {
      if (listOfFiles[i].isFile()) {
        FileStrategy loadStrategy = new FileStrategy(listOfFiles[i].getPath(), log);
        loadAdditionalRoute(loadStrategy);
      }
    }
    // At this point the current state should be WaitingForRouteSelection
  }

  /**
   * Helper method which resets all the variables which maintain the route following state to their default values
   */
  protected void resetRouteStateVariables() {
    activeRoute = null;
    currentSegment = null;
    currentSegmentIndex = 0;
    currentWaypointIndex = 0;
    downtrackDistance = 0;
    crossTrackDistance = 0;
    routeStateSeq = 0;
    recievedLeftRouteEvents = 0;
    currentSegmentDowntrack = 0;
  }

  /**
   * Function which coordinates state transitions and the timeout timers
   *
   * @param event the event which will be used to determine the next state
   */
  protected void next(WorkerEvent event) {
    currentStateIndex = transition[event.ordinal()][currentStateIndex];
    log.info("Route State = " + getCurrentState());
    // Publish the new route state
    routeManager.publishRouteState(getRouteStateTopicMsg(routeStateSeq, routeManager.getTime()));
    routeManager.publishRouteEvent(getRouteEventTopicMsg(event));
  }

  /**
   * Handles the given event and updates the current state
   *
   * @param event the event to be handled
   */
  protected void handleEvent(WorkerEvent event) {
    log.info("Handled route event: " + event.toString());
    switch (event) {
      case FILES_LOADED:
        break;
      case ROUTE_SELECTED:
        break;
      case ROUTE_COMPLETED:
        break;
      case LEFT_ROUTE:
        recievedLeftRouteEvents = 0; // Reset the left route count before transitioning to a new state
        break;
      case SYSTEM_FAILURE:
        routeManager.shutdown();
        break;
      case SYSTEM_NOT_READY:
        break;
      case ROUTE_ABORTED:
        resetRouteStateVariables();
        break;
      case ROUTE_STARTED:
        break;
      default:
        log.info("Route was provided with an unsupported event");
    }
    // Update current state
    next(event);
  }

  /**
   * Gets the current state of this FSM
   *
   * @return the current state
   */
  public WorkerState getCurrentState() {
    return states[currentStateIndex];
  }

  /**
   * Loads a route into memory using the provided route loading strategy
   *
   * @param loadStrategy
   */
  protected void loadAdditionalRoute(IRouteLoadStrategy loadStrategy) {
    Route route = loadStrategy.load();
    if (route == null) {
      log.warn("Failed to load a route");
      return;
    }
    route
      .setRouteID(route.getRouteName()); //TODO come up with better method of defining the route id
    availableRoutes.put(route.getRouteID(), route);
    handleEvent(WorkerEvent.FILES_LOADED);
  }

  /**
   * Returns true when the host vehicle has passed the end of the current route segment
   * @return indication of vehicle in next segment
   */
  protected boolean atNextSegment() {
    return currentSegment.downTrackDistance(hostVehicleLocation) > currentSegment.length();
  }

  /**
   * Returns true if crossTrackDistance is so large that the vehicle can no longer be considered on the route
   *
   * @return vehicle on route status
   */
  protected boolean leftRouteVicinity() {
    RouteWaypoint wp = currentSegment.getDowntrackWaypoint();
    return crossTrackDistance < wp.getMinCrossTrack() || wp.getMaxCrossTrack() < crossTrackDistance;
  }

  /**
   * Gets the collection of available routes
   * @return a collection of routes
   */
  protected Collection<Route> getAvailableRoutes() {
    return availableRoutes.values();
  }

  /**
   * Function to be used in a callback for the setActiveRoute service
   *
   * @param routeID The route
   * @return the service response
   */
  public byte setActiveRoute(String routeID) {
    Route route = availableRoutes.get(routeID);
    // Check if the specified route exists.
    if (route == null) {
      return SetActiveRouteResponse.NO_ROUTE;
    } else {
      resetRouteStateVariables(); // Reset all route state variables when a new route is selected
      activeRoute = route;

      handleEvent(WorkerEvent.ROUTE_SELECTED);
      return SetActiveRouteResponse.NO_ERROR;
    }
  }

  /**
   * Starts the currently active route and returns a byte (unit8) indicating the success of the start
   * Requests to start the route are validated for feasibility
   *
   * @return The error code (See cav_srvs.StartActiveRoute for possible options)
   */
  public byte startActiveRoute() {
    if (activeRoute == null) {
      return StartActiveRouteResponse.NO_ACTIVE_ROUTE;
    }
    if (getCurrentState() == WorkerState.FOLLOWING_ROUTE) {
      return StartActiveRouteResponse.ALREADY_FOLLOWING_ROUTE;
    }
    int startingIndex = getValidStartingWPIndex();
    log.info("Starting waypoint index = " + startingIndex);
    if (startingIndex == -1) {
      return StartActiveRouteResponse.INVALID_STARTING_LOCATION;
    } else {
      if (startRouteAtIndex(startingIndex)) {
        routeManager.publishActiveRoute(getActiveRouteTopicMsg());
        return StartActiveRouteResponse.NO_ERROR;
      } else {
        return StartActiveRouteResponse.INTERNAL_ERROR;
      }
    }
  }

  /**
   * Gets the index of the first waypoint which can serve as a valid starting point for a route
   *
   * @return the valid waypoint index (-1 if not waypoint is valid)
   */
  protected int getValidStartingWPIndex() {
    if (activeRoute == null) {
      return -1;
    }
    int count = 0;
    double maxJoinDistance = activeRoute.getMaxJoinDistance();
    log.debug("getValidStartingWPIndex: lat = " + hostVehicleLocation.getLatitude() + ", lon = " + hostVehicleLocation.getLongitude());
    for (RouteSegment seg : activeRoute.getSegments()) {
      double crossTrack = seg.crossTrackDistance(hostVehicleLocation);
      double downTrack = seg.downTrackDistance(hostVehicleLocation);

      log.info("crosstrack to waypoint " + count + " = " + crossTrack);

      if (Math.abs(crossTrack) < maxJoinDistance) {
        if (count == 0 && downTrack < -0.0 && Math.abs(downTrack) < maxJoinDistance) {
          return 0;
        } else if (downTrack > -0.0 && downTrack < seg.length()) {
          return count + 1;
        }
      }
      count++;
    }

    return -1;
  }

  /**
   * Setup the following of a new route starting from the specified waypoint index on the route
   *
   * @param index the index of the first waypoint to start from.
   *              An additional segment will be added from the vehicle to this starting point
   * @return true if able to start route at the specified index
   */
  protected boolean startRouteAtIndex(int index) {
    // Insert a starting waypoint at the current vehicle location which is connected to the route
    RouteWaypoint downtrackWP = activeRoute.getWaypoints().get(index);
    RouteWaypoint startingWP = new RouteWaypoint(downtrackWP); // Deep copy of downtrack waypoint
    startingWP.setLocation(new Location(hostVehicleLocation)); // Don't want waypoint and vehicle to reference same location object

    try {
      activeRoute.insertWaypoint(startingWP, index);
    } catch (Exception e) {
      log.info("Exception caught when inserting route starting waypoint Exception = " + e);
      log.info("Could not join the route from the current location");
      // If we can't join the route return
      return false;
    }

    currentSegment = activeRoute.getSegments().get(index);
    currentSegmentIndex = index;
    currentWaypointIndex = index + 1; // The current waypoint should be the downtrack one
    downtrackDistance = activeRoute.lengthOfSegments(0, index - 1);
    crossTrackDistance = currentSegment.crossTrackDistance(hostVehicleLocation);

    handleEvent(WorkerEvent.ROUTE_STARTED);
    return true;
  }

  /**
   * Aborts the currently active route and returns a byte (unit8) indicating the success of the abort request
   *
   * @return The error code (See cav_srvs.AbortActiveRoute for possible options)
   */
  public byte abortActiveRoute() {
    if (activeRoute == null) {
      return cav_srvs.AbortActiveRouteResponse.NO_ACTIVE_ROUTE;
    }
    log.info("Aborting current route by request");
    handleEvent(WorkerEvent.ROUTE_ABORTED);
    return cav_srvs.AbortActiveRouteResponse.NO_ERROR;
  }


  /**
   * Function to be used as a callback for the arrival of NavSatFix messages
   *
   * @param msg The received message
   */
  protected void handleNavSatFixMsg(NavSatFix msg) {
    switch (msg.getStatus().getStatus()) {
      case NavSatStatus.STATUS_NO_FIX:
        log.warn("Gps data with no fix received by route");
        return;
      case NavSatStatus.STATUS_FIX:
        hostVehicleLocation
          .setLocationData(msg.getLatitude(), msg.getLongitude(), 0); // Used to be msg.getAltitude()
        break;
      case NavSatStatus.STATUS_SBAS_FIX:
        //TODO: Handle this variant
        hostVehicleLocation
          .setLocationData(msg.getLatitude(), msg.getLongitude(), 0); // Used to be msg.getAltitude()
        break;
      case NavSatStatus.STATUS_GBAS_FIX:
        //TODO: Handle this variant
        hostVehicleLocation
          .setLocationData(msg.getLatitude(), msg.getLongitude(), 0); // Used to be msg.getAltitude()
        break;
      default:
        //TODO: Handle this variant maybe throw exception?
        log.warn("Unknown nav sat fix status type: " + msg.getStatus().getStatus());
        return;
    }

    if (getCurrentState() != WorkerState.FOLLOWING_ROUTE || activeRoute == null) {
      return;
    }

    // Loop to find current segment. This allows for small breaks in gps data
    while (atNextSegment()) { // TODO this might be problematic on tight turns
      currentSegmentIndex++;
      currentWaypointIndex++;
      // Check if the route has been completed
      if (currentSegmentIndex >= activeRoute.getSegments().size()) {
        handleEvent(WorkerEvent.ROUTE_COMPLETED);
        return;
      }
      currentSegment = activeRoute.getSegments().get(currentSegmentIndex);
    }

    // Update downtrack distance
    currentSegmentDowntrack = currentSegment.downTrackDistance(hostVehicleLocation);
    downtrackDistance = Math.max(0.0, activeRoute.lengthOfSegments(0, currentSegmentIndex - 1) + currentSegmentDowntrack);

    // Update crosstrack distance
    crossTrackDistance = currentSegment.crossTrackDistance(hostVehicleLocation);

    log.debug("Downtrack: " + downtrackDistance + ", Crosstrack: " + crossTrackDistance);
    log.debug("Downtrack Waypoint: " + currentWaypointIndex);

    if (leftRouteVicinity()) {
      recievedLeftRouteEvents++;
      if (recievedLeftRouteEvents >= requiredLeftRouteCount) {
        handleEvent(WorkerEvent.LEFT_ROUTE);
      }
    } else {
      recievedLeftRouteEvents = 0; // reset count to 0 if we are back on the route
    }

    // Publish updated route information
    routeManager.publishRouteState(getRouteStateTopicMsg(routeStateSeq, routeManager.getTime()));
  }

  /**
   * Function to be used as a callback for received system alert messages
   *
   * @param msg the system alert message
   */
  protected void handleSystemAlertMsg(SystemAlert msg) {
    switch (msg.getType()) {
      case cav_msgs.SystemAlert.CAUTION:
        // TODO: Handle this message type
        break;
      case cav_msgs.SystemAlert.WARNING:
        // TODO: Handle this message type
        break;
      case cav_msgs.SystemAlert.FATAL:
        handleEvent(WorkerEvent.SYSTEM_FAILURE);
        log.info("Received system fatal on system_alert and is abandoning the route");
        break;
      case cav_msgs.SystemAlert.NOT_READY:
        handleEvent(WorkerEvent.SYSTEM_NOT_READY);
        break;
      case cav_msgs.SystemAlert.DRIVERS_READY:
        systemOkay = true;
        log.info("Received system ready on system_alert and is starting to publish");
        break;
      case cav_msgs.SystemAlert.SHUTDOWN:
        log.info("Received a shutdown message");
        routeManager.shutdown();
        break;
      default:
        //TODO: Handle this variant maybe throw exception?
        log.warn("System alert message received with unknown type: " + msg.getType());
    }
  }

  /**
   * Returns an active route message to publish
   *
   * @return route message
   */
  protected cav_msgs.Route getActiveRouteTopicMsg() {
    if (activeRoute == null) {
      log.warn("Request for active route message when current segment is null");
      return messageFactory.newFromType(cav_msgs.Route._TYPE);
    }
    return activeRoute.toMessage(messageFactory);
  }

  /**
   * Returns a route state message to be published
   *
   * @param seq  The header sequence
   * @param time the time
   * @param event A worker event which is this message will serve as a notification for
   * @return route state message
   */
  protected RouteState getRouteStateTopicMsg(int seq, Time time) {
    RouteState routeState = messageFactory.newFromType(RouteState._TYPE);
    // Set the state of route following
    switch (getCurrentState()) {
      case LOADING_ROUTES:
        routeState.setState(RouteState.LOADING_ROUTES);
        break;
      case ROUTE_SELECTION:
        routeState.setState(RouteState.ROUTE_SELECTION);
        break;
      case WAITING_TO_START:
        routeState.setState(RouteState.WAITING_TO_START);
        break;
      case FOLLOWING_ROUTE:
        routeState.setState(RouteState.FOLLOWING_ROUTE);
        break;
      default:
        routeState.setState(RouteState.ROUTE_SELECTION);
        log.warn("Sending a route state message an unsupported state was set. Defaulted to ROUTE_SELECTION");
        break;
    }

    if (activeRoute != null) {
      routeState.setCrossTrack(crossTrackDistance);
      routeState.setRouteID(activeRoute.getRouteID());
      routeState.setDownTrack(downtrackDistance);
      if (currentSegment != null) {
        routeState.setSegmentDownTrack(currentSegmentDowntrack);
        routeState.setCurrentSegment(currentSegment.toMessage(messageFactory, currentWaypointIndex));
        routeState.setLaneIndex((byte) currentSegment.determinePrimaryLane(crossTrackDistance));
      }
    }

    std_msgs.Header hdr = messageFactory.newFromType(std_msgs.Header._TYPE);
    hdr.setFrameId("0");
    hdr.setSeq(seq);
    hdr.setStamp(time);
    return routeState;
  }

  protected RouteEvent getRouteEventTopicMsg(WorkerEvent event) {
      RouteEvent routeEvent = messageFactory.newFromType(RouteEvent._TYPE);
      // Set a recent event which this message serves as a notification of
      switch (event) {
      case ROUTE_SELECTED:
          routeEvent.setEvent(RouteEvent.ROUTE_SELECTED);
          break;
      case ROUTE_STARTED:
          routeEvent.setEvent(RouteEvent.ROUTE_STARTED);
          break;
      case ROUTE_COMPLETED:
          routeEvent.setEvent(RouteEvent.ROUTE_COMPLETED);
          break;
      case LEFT_ROUTE:
          routeEvent.setEvent(RouteEvent.LEFT_ROUTE);
          break;
      case ROUTE_ABORTED:
          routeEvent.setEvent(RouteEvent.ROUTE_ABORTED);
          break;
      default:
          routeEvent.setEvent(RouteEvent.NONE);
      }
      
      return routeEvent;
  }
  
  //  /**  TODO: Add once we have tim messages
  //   * Function for used in Tim topic callback. Used to update waypoints on a route
  //   * @param msg The tim message
  //   */
  //   void handleTimMsg(cav_msgs.Tim msg);
}

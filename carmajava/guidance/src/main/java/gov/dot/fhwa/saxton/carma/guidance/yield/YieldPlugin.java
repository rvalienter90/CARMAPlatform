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

 // The Yield Plugin exists to resolve vehicle trajectory conflicts, or in English, to handle the
 // situation where two vehicles, based on existing trajectories or on trajectories that are still
 // under consideration, will collide at some point in the near future.  A decision is made at a
 // higher level (based on urgencies, priorities, or other factors) to determine which of the two
 // vehicles should replan its trajectory, so when the Yield plugin is "activated" on a vehicle,
 // it doesn't have to make that determination.  It knows that it was called, and therefore its
 // responsibility is to change its vehicle's trajectory to avoid the collision.

 // Version 1.0.0 will work as follows.  It will be passed the vehicle trajectory (or somehow gain
 // access to it) along with a collision "point" or region (that could have a starting and ending
 // downtrack distance (d) and time (t)).  It will request a new blank trajectory and it will
 // calculate the downtrack distance (d - Delta) where it will be "safe" from the collision,
 // effectively arriving behind the other vehicle instead of colliding with it.  To accomplish this
 // it will loop through the maneuvers (Mi) in the "old" vehicle trajectory, copying each one to the
 // new trajectory until it finds the maneuver, Mc, that would cause the collision.  At this point it
 // allocates a new, blank maneuver.  It makes whatever function call or calls that are necessary to
 // populate the maneuver with a path(?) that has the same starting information as Mc, but ends at
 // d - Delta instead of at d.  For now, that's it.
 
 // ??? -- This partially filled trajectory is returned to the planner or arbitrator which does
 // something TBD.  Alternatively, it calls ACC(?) to fill out the rest of the trajectory and returns
 // that as a complete new trajectory that avoids the collision.  This may be sufficient for initial
 // testing.  Alternatively, (or for version 1.1.0), this new trajectory can be run through collision
 // checking again, and if another collision is found (at a higher d value than before), Yield is
 // called again.  If each time Yield is called for this single trajectory, the collision is further
 // downtrack (d is always getting larger, and d-delta is always located in a new Maneuver), then
 // this process will terminate.  We should be careful to avoid loops where even though we move further
 // downtrack, we are still redoing the same maneuver over and over again.

 // Other future implementations may include having Yield planning the entire new trajectory.  It would
 // process everything through Mc as before, but instead of returning control back to the arbitrator,
 // planner, or guidance (???), it would recursively (or maybe iteratively) call the planner/arbitrator
 // to fill in the rest of the trajectory.  This is all TBD later after v1.0.0 is build and tested.

package gov.dot.fhwa.saxton.carma.guidance.yield;

import cav_msgs.NewPlan;
import cav_msgs.PlanStatus;
import cav_msgs.PlanType;
import cav_msgs.MobilityRequest;
import cav_msgs.MobilityPath;
import gov.dot.fhwa.saxton.carma.guidance.ManeuverPlanner;
import gov.dot.fhwa.saxton.carma.guidance.arbitrator.TrajectoryPlanningResponse;
import gov.dot.fhwa.saxton.carma.guidance.conflictdetector.ConflictSpace;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.IManeuver;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.IManeuverInputs;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.LongitudinalManeuver;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.ManeuverType;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.SimpleManeuverFactory;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.SlowDown;
import gov.dot.fhwa.saxton.carma.guidance.mobilityrouter.MobilityPathHandler;
import gov.dot.fhwa.saxton.carma.guidance.mobilityrouter.MobilityRequestHandler;
import gov.dot.fhwa.saxton.carma.guidance.mobilityrouter.MobilityRequestResponse;
import gov.dot.fhwa.saxton.carma.guidance.params.ParameterSource;
import gov.dot.fhwa.saxton.carma.guidance.plugins.AbstractPlugin;
import gov.dot.fhwa.saxton.carma.guidance.plugins.IStrategicPlugin;
import gov.dot.fhwa.saxton.carma.guidance.plugins.PluginServiceLocator;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.IPublisher;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.ISubscriber;
import gov.dot.fhwa.saxton.carma.guidance.trajectory.Trajectory;
import gov.dot.fhwa.saxton.carma.guidance.util.SpeedLimit;
import java.util.*;

public class YieldPlugin extends AbstractPlugin implements IStrategicPlugin, MobilityRequestHandler, MobilityPathHandler {

    /* Constants */
    /*TODO: Remove -- From NegotiationReceiver
    protected static final double TARGET_SPEED_EPSILON = 0.1;
    protected static final long SLEEP_DURATION = 1000; //TODO: Was 50
    protected SimpleManeuverFactory maneuverFactory;
    protected ManeuverPlanner       planner;
    */
    
    /* Class Variables */
    /*TODO: Remove -- From NegotiationReceiver
    protected ISubscriber<NewPlan> planSub_;
    protected IPublisher<PlanStatus> statusPub_;
    protected Map<String, List<LongitudinalManeuver>> planMap = new Hashtable<>(); //planId to a list of maneuvers
    protected Queue<String> replanQueue = new LinkedList<>(); //a task list for replan jobs indicated by planIds
    protected double maxAccel_ = 2.5;
    protected String currentPlanId = null; //the next planId for replan
    protected boolean includeAccelDist_ = true;
    protected double slowSpeedFraction_ = 0.8;
    protected double initialTimeGap_ = 1.0;
    */

    // For now, there are two states.  One in which there is no trajectory stored, and one in which there is.  Initially we are
    // in the former state - call it state 0.  That is also the start state.  To transition out of this state, we must receive a
    // call to handleMobilityPathMessageWithConflict() or handleMobilityRequestMessage().  In either case we are passed a
    // trajectory (and a collision point?, and other information?).  We store this trajectory (and other information) in class
    // variables, and if successful, transition from state 0 to state 1 by changing the value of the class variable state_ to 1.
    
    // To transition back to state 0, we must receive a call to planTrajectory().  If planTrajectory() is called while in state 0,
    // nothing happens.  It returns.  If it is called in state 1, then this is where the real work is done.  As described above, a
    // new trajectory is created, replacing the old one, where the collision is avoided by having our vehicle arrive behind the
    // collision point at the collision time (the details of which are TBD).

    protected int        state_       = 0;
    protected Trajectory trajectory_  = null;
    // Place other things to rememeber (such as the collision point) here.
///TODO(BDR): Got This Far.
    // Constructor
    public YieldPlugin(PluginServiceLocator pluginServiceLocator) {
        super(pluginServiceLocator);
        version.setName("Yield Plugin");
        version.setMajorRevision(1);
        version.setIntermediateRevision(0);
        version.setMinorRevision(0);
    }

    @Override
    public void onInitialize() {
        ParameterSource params = pluginServiceLocator.getParameterSource();
        maneuverFactory = new SimpleManeuverFactory(this);
        planner = pluginServiceLocator.getManeuverPlanner();
        /* Todo: 
        planSub   = pubSubService.getSubscriberForTopic("new_plan", NewPlan._TYPE);
        statusPub = pubSubService.getPublisherForTopic("plan_status", PlanStatus._TYPE);
        */
        log.info("Yield plugin initialized with NO parameters");
    }

    @Override
    public void onResume() {
        //Todo: planSub.registerOnMessageCallback(this::onPlanReceived);
        setAvailability(true);
        log.info("Yield plugin resumed");
    }
    TODO: Main documentation goes here
    @Override
    public void loop() throws InterruptedException {
        /*Todo:
        if((!replanQueue.isEmpty()) && currentPlanId == null) {
            currentPlanId = replanQueue.poll();
            log.info("Loop found a new plan id " + currentPlanId + " . Calling arbitrator to replan.");
            // TODO before we start to replan we need to add some checks
            // start replan
            pluginServiceLocator.getArbitratorService().notifyTrajectoryFailure();
        } else {
            //We are in one of three situations:
            //  1.Waiting for replanning
            //  2.Replanning
            //  3.No new replans avaliable
            sleep was here
        }
        */
        Thread.sleep(SLEEP_DURATION);
        log.info("Yield main loop");
    }

    @Override
    public void onSuspend() {
        //TODO: planSub.registerOnMessageCallback(this::noAction);
        setAvailability(false);
        log.info("Yield plugin suspended");
    }

    @Override
    public void onTerminate() {
        //TODO: planSub.registerOnMessageCallback(this::noAction);
        log.info("Yield plugin terminated");
    }

    @Override
    public TrajectoryPlanningResponse planTrajectory(Trajectory trajectory, double expectedEntrySpeed) {
        /*
        if(currentPlanId != null) {
            List<LongitudinalManeuver> maneuvers = planMap.get(currentPlanId);
            double endDist = 0;
            for (LongitudinalManeuver m : maneuvers) {
                endDist = Math.max(endDist, m.getEndDistance());
            }
            // check if we have enough space to insert maneuvers
            if (endDist > trajectory.getEndLocation()) {
                log.info("The endDist of plan " + currentPlanId + " is larger than trajectory endLocation " + trajectory.getEndLocation());
                TrajectoryPlanningResponse response = new TrajectoryPlanningResponse();
                response.requestLongerTrajectory(endDist);
                log.info("Requesting for a longer trajectory ending at " + endDist);
                return response;
            }
            for (LongitudinalManeuver m : maneuvers) {
                log.info(String.format("Insert a new maneuver {start=%.2f,end=%.2f,startSpeed=%.2f,endSpeed=%.2f}",
                        m.getStartDistance(), m.getEndDistance(), m.getStartSpeed(), m.getTargetSpeed()));
                trajectory.addManeuver(m);
            }
            // prevent from duplicate replan but keep the record of handled planId
            planMap.put(currentPlanId, new LinkedList<>());
            // publish plan status
            PlanStatus status = statusPub_.newMessage();
            status.getHeader().setFrameId("0");
            status.setPlanId(currentPlanId);
            status.setAccepted(true);
            statusPub_.publish(status);
            currentPlanId = null;
        }
        */
        return new TrajectoryPlanningResponse();
    }
    
    //Todo: Both of the entry points below can call the same function to handle the collision

	@Override
	public MobilityRequestResponse handleMobilityRequestMessage(MobilityRequest msg, boolean hasConflict,
			ConflictSpace conflictSpace) {
                // Primary Entry Point
                
                Trajectory traj = pluginServiceLocator.getArbitratorService().getCurrentTrajectory();
                
                IManeuver maneuver = traj.getManeuverAt(conflictSpace.getStartDowntrack(), ManeuverType.LONGITUDINAL);
                SlowDown slowDown = new SlowDown(this);
                pluginServiceLocator.getManeuverPlanner().planManeuver(slowDown, startDist, endDist);
                pluginServiceLocator.getArbitratorService().planSubtrajectoryRecursively(startDist, endDist);
                pluginServiceLocator.getArbitratorService().requestNewPlan();
		return null;
	}

	@Override
	public void handleMobilityPathMessageWithConflict(MobilityPath msg, boolean hasConflict,
			ConflictSpace conflictSpace) {
                // Primary Entry Point		
	}

    /*TODO: Good reference material
    private void onPlanReceived(NewPlan plan) {
        String id = plan.getPlanId();
        //TODO also need to consider the expiration time for a plan
        if(!planMap.containsKey(id)) {
            log.info("Received new plan with planId: " + id);
            // TODO this maneuvers list may need to include lateral maneuvers in the future
            List<LongitudinalManeuver> maneuvers = new LinkedList<>();
            String planInputs = plan.getInputs();
            if(plan.getType().getType() == PlanType.CHANGE_LANE_LEFT || plan.getType().getType() == PlanType.CHANGE_LANE_RIGHT) {
                String[] splitInput = planInputs.split(", ");
                double proposedLaneChangeStartDist = Double.parseDouble(splitInput[0].split(":")[1]);
                double proposedLaneChangeStartSpeed = Double.parseDouble(splitInput[1].split(":")[1]) - TARGET_SPEED_EPSILON;

                // Assume the neighbor vehicle is travelling exactly beside us at the same speed and forward vehicle
                // is also travelling at our speed when following mvrs begin.
                // We first need to slow down fairly quickly while the neighbor vehicle waits (continues to cruise).
                // Once the gap to fwd vehicle opens enough, then the neighbor vehicle changes lanes (assume already have
                // the right gap in front of him, since we presumably did) while we speed up again to his original
                // cruising speed to follow him. ACC will be a handy feature here!
                //
                // So that he can start his lane change immediately upon crossing the threshold of the new
                // FutureLongitudinalManeuver space, we need to complete our slowing & gap growth before that point.
                // We need to double the time gap (vehicle length is negligible if we have ACC working).  We will reduce our speed
                // to some fraction of its initial value to grow the gap.  To get to that lower speed,
                // we assume we can decel at 80% of our maximum allowed acceleration.
                //
                // This planning may be occurring well uptrack of where the maneuver actually needs to occur, so we
                // cannot assume that our current speed is relevant.  What we will assume is that we will be going
                // roughly the same speed that the merging vehicle thinks it will be going at the beginning of its lane
                // change.  This speed will be our "default" speed through that part of the route (what we would be
                // doing if not for this slowdown interruption).
                IManeuverInputs mInputs = planner_.getManeuverInputs();
                double accel = 0.8*maxAccel_; //assumed conservatively, m/s^2
                double initialSpeed = 1.1*proposedLaneChangeStartSpeed; //allows extra distance for some control error
                double responseLag = mInputs.getResponseLag();
                double slowSpeed = slowSpeedFraction_*proposedLaneChangeStartSpeed;
                double initialLagDist = responseLag * initialSpeed; //time to respond to slowdown cmd

                //this will still work in the unlikely case that initialSpeed < slowSpeed (shouldn't happen in TO 13 testing)
                double distToDecel = initialLagDist + 0.5*(initialSpeed + slowSpeed) * Math.abs(initialSpeed - slowSpeed)/accel;
                double finalLagDist = responseLag * slowSpeed;
                double distToAccel = finalLagDist + 0.5*(slowSpeed + proposedLaneChangeStartSpeed) *
                                                    0.5*(proposedLaneChangeStartSpeed - slowSpeed);

                double timeToDecel = responseLag + Math.abs(initialSpeed - slowSpeed)/accel;
                //calculate how big the forward gap is growing while we decelerate and how much more gap we will need
                // in order to double the gap size
                double initialGap = initialTimeGap_*proposedLaneChangeStartSpeed;
                double gapGrowthDuringDecel = initialSpeed*timeToDecel - distToDecel; //assumes fwd vehicle going same initial speed
                double distAtLowerSpeed = 0.0;
                if (gapGrowthDuringDecel < initialGap) {
                    distAtLowerSpeed = (initialGap - gapGrowthDuringDecel)/Math.abs(initialSpeed - slowSpeed)*slowSpeed;
                }

                double totalDist = distToDecel + distAtLowerSpeed + (includeAccelDist_ ? distToAccel : 0.0);
                log.debug("V2V", "calculated slowSpeed = " + slowSpeed + ", distAtLowerSpeed = "
                            + distAtLowerSpeed + ", distToDecel = " + distToDecel + ", distToAccel = " + distToAccel
                            + ", includeAccelDist = " + includeAccelDist_ + ", totalDist = " + totalDist);
                log.debug("V2V", "initialGap = " + initialGap + ", gapGrowthDuringDecel = " + gapGrowthDuringDecel
                            + ", timeToDecel = " + timeToDecel);

                double startLocation = proposedLaneChangeStartDist - totalDist;
                log.debug("V2V", "calculated startLocation = " + startLocation);
                double locAfterReplan = mInputs.getDistanceFromRouteStart() + 0.1*initialSpeed; //assume new trajectory can begin executing in 100 ms
                if (startLocation < locAfterReplan) {
                    log.warn("V2V", "calculated - insufficient distance for us to slow down. They are "
                                + (locAfterReplan - startLocation) + " m late. Sending NACK.");
                    // reject this plan
                    // TODO now it rejects a plan forever, need allow back and forth in future
                    statusPub_.publish(this.buildPlanStatus(id, false));
                    planMap.put(id, new LinkedList<>());
                    return;
                }

                //verify that we don't have a different roadway speed limit at our starting location than at the ending location
                SpeedLimit startingSpeedLimit = pluginServiceLocator.getRouteService().getSpeedLimitAtLocation(startLocation);
                if (startingSpeedLimit.getLimit() < proposedLaneChangeStartSpeed) {
                    log.warn("V2V", "Detected speed limit change within the initially planned tactic to accommodate a lane change.");
                }

                //build maneuvers for this vehicle
                double endSlowdown = startLocation + distToDecel;
                double endConstant = endSlowdown + distAtLowerSpeed;
                double endSpeedup = endConstant + distToAccel;
                log.debug("MVR", "onPlanReceived: endSlowdown = " + endSlowdown
                            + ", endConstant = " + endConstant + ", endSpeedup = " + endSpeedup);

                //create a list of maneuvers that we should execute
                List<String> maneuversString = new ArrayList<>();
                maneuversString.add(startLocation + ":" + endSlowdown + ":" + proposedLaneChangeStartSpeed + ":" + slowSpeed);
                if (distAtLowerSpeed > 0.1) {
                    maneuversString.add(endSlowdown + ":" + endConstant + ":" + slowSpeed + ":" + slowSpeed);
                }
                maneuversString.add(endConstant + ":" + endSpeedup + ":" + slowSpeed + ":" + proposedLaneChangeStartSpeed);

                //attempt to plan each maneuver
                int index = 0;
                for(String m : maneuversString) {
                    String[] params = m.split(":");
                    //the params format is <startDistance>:<endDistance>:<startSpeed>:<endSpeed>
                    double[] paramsInDouble = {Double.parseDouble(params[0]), Double.parseDouble(params[1]),
                                               Double.parseDouble(params[2]), Double.parseDouble(params[3])};
                    LongitudinalManeuver maneuver = maneuverFactory_.createManeuver(paramsInDouble[2], paramsInDouble[3]);
                    maneuver.setSpeeds(paramsInDouble[2], paramsInDouble[3]);
                    maneuver.setMaxAccel(maxAccel_);
                    planner_.planManeuver(maneuver, paramsInDouble[0], paramsInDouble[1]);

                    // check the adjusted target speed to see if it can plan without huge adjustment
                    // if not it will show a negative status on PlanStatus message and ignore this planId in future
                    if(Math.abs(maneuver.getTargetSpeed() - paramsInDouble[3]) > TARGET_SPEED_EPSILON) {
                        log.warn("V2V", "Cannot plan the proposed maneuvers within accel_max limits. Failed maneuver #" + index
                                    + ". maneuver target speed = " + maneuver.getTargetSpeed());
                        //            + ". Sending Ack failure message.");
                        //statusPub_.publish(this.buildPlanStatus(id, false));
                        //planMap.put(id, new LinkedList<>());
                        //return;
                    }
                    maneuvers.add(maneuver);
                    ++index;
                }
            }
            log.info("V2V", "Maneuvers defined - adding them to queue for replanning.");
            planMap.put(id, maneuvers);
            replanQueue.add(id);
        }
    }
    */
}

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

package gov.dot.fhwa.saxton.carma.guidance.trajectory;

import java.util.List;
import java.util.ArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import gov.dot.fhwa.saxton.carma.guidance.maneuvers.IManeuver;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.ISimpleManeuver;
import gov.dot.fhwa.saxton.carma.guidance.util.ILogger;
import gov.dot.fhwa.saxton.carma.guidance.util.ILoggerFactory;
import gov.dot.fhwa.saxton.carma.guidance.util.LoggerManager;

public class TrajectoryValidatorTest {

  @Before
  public void setup() {
    ILoggerFactory mockFact = mock(ILoggerFactory.class);
    ILogger mockLogger = mock(ILogger.class);
    when(mockFact.createLoggerForClass(anyObject())).thenReturn(mockLogger);
    LoggerManager.setLoggerFactory(mockFact);
    tv = new TrajectoryValidator();
  }

  @Test
  public void testSuccess() {
    TrajectoryValidationConstraint tvc1 = mock(TrajectoryValidationConstraint.class);
    when(tvc1.getResult()).thenReturn(new TrajectoryValidationResult());

    TrajectoryValidationConstraint tvc2 = mock(TrajectoryValidationConstraint.class);
    when(tvc2.getResult()).thenReturn(new TrajectoryValidationResult());

    TrajectoryValidationConstraint tvc3 = mock(TrajectoryValidationConstraint.class);
    when(tvc3.getResult()).thenReturn(new TrajectoryValidationResult());

    tv.addValidationConstraint(tvc1);
    tv.addValidationConstraint(tvc2);
    tv.addValidationConstraint(tvc3);

    Trajectory t = mock(Trajectory.class);
    List<IManeuver> maneuvers = new ArrayList<>();

    maneuvers.add(mock(ISimpleManeuver.class));
    maneuvers.add(mock(ISimpleManeuver.class));
    maneuvers.add(mock(ISimpleManeuver.class));

    when(t.getManeuvers()).thenReturn(maneuvers);

    assertTrue(tv.validate(t));

    verify(tvc1, times(3)).visit((ISimpleManeuver) any());
    verify(tvc2, times(3)).visit((ISimpleManeuver) any());
    verify(tvc3, times(3)).visit((ISimpleManeuver) any());
  }

  @Test
  public void testFailure() {
    TrajectoryValidationConstraint tvc1 = mock(TrajectoryValidationConstraint.class);
    when(tvc1.getResult()).thenReturn(new TrajectoryValidationResult());

    TrajectoryValidationConstraint tvc2 = mock(TrajectoryValidationConstraint.class);
    when(tvc2.getResult()).thenReturn(new TrajectoryValidationResult());

    TrajectoryValidationConstraint tvc3 = mock(TrajectoryValidationConstraint.class);
    when(tvc3.getResult()).thenReturn(new TrajectoryValidationResult(new TrajectoryValidationError("Test", new ArrayList<IManeuver>())));

    tv.addValidationConstraint(tvc1);
    tv.addValidationConstraint(tvc2);
    tv.addValidationConstraint(tvc3);

    Trajectory t = mock(Trajectory.class);
    List<IManeuver> maneuvers = new ArrayList<>();

    maneuvers.add(mock(ISimpleManeuver.class));
    maneuvers.add(mock(ISimpleManeuver.class));
    maneuvers.add(mock(ISimpleManeuver.class));

    when(t.getManeuvers()).thenReturn(maneuvers);

    assertFalse(tv.validate(t));

    verify(tvc1, times(3)).visit((ISimpleManeuver) any());
    verify(tvc2, times(3)).visit((ISimpleManeuver) any());
    verify(tvc3, times(3)).visit((ISimpleManeuver) any());
  }

  protected TrajectoryValidator tv;
}

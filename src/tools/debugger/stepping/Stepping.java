package tools.debugger.stepping;

import tools.SourceCoordinate.FullSourceCoordinate;
/**
 *
 * Manage the actor message stepping.
 *
 */
public class Stepping {

  public enum SteppingType{
    STEP_INTO,
    STEP_OVER
  }

  private StepActorOperation stepActor;

  public Stepping(final StepActorOperation stepActor) {
    this.stepActor = stepActor;
  }

  public boolean isStepInto(final FullSourceCoordinate source) {
    if (stepActor != null) {
      if (stepActor.getSteppingType() == SteppingType.STEP_INTO && source.equals(stepActor.getSourceSection())) {
        stepActor = null;
        return true;
      }
    }
    return false;
  }

}

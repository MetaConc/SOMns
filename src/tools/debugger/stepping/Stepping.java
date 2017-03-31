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

  public boolean isStepOperation(final FullSourceCoordinate source, final SteppingType type) {
    if (stepActor != null) {
      if (stepActor.getSteppingType() == type && source.equals(stepActor.getSourceSection())) {
        return true;
      }
    }
    return false;
  }

}

package som.primitives.actors;

import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.source.SourceSection;

import som.interpreter.actors.Actor;
import som.interpreter.actors.SFarReference;
import som.interpreter.nodes.nary.BinaryComplexOperation;
import som.primitives.ObjectPrims.IsValue;
import som.primitives.ObjectPrimsFactory.IsValueFactory.IsValueNodeGen;
import som.primitives.Primitive;
import som.primitives.actors.PromisePrims.IsActorModule;
import som.vm.VmSettings;
import som.vm.constants.KernelObj;
import som.vmobjects.SClass;
import tools.concurrency.ActorExecutionTrace;
import tools.concurrency.TracingActors.TracingActor;


@GenerateNodeFactory
@Primitive(primitive = "actors:createFromValue:", selector  = "createActorFromValue:",
           specializer = IsActorModule.class)
public abstract class CreateActorPrim extends BinaryComplexOperation {
  @Child protected IsValue isValue;

  protected CreateActorPrim(final boolean eagWrap, final SourceSection source) {
    super(eagWrap, source);
    isValue = IsValueNodeGen.createSubNode();
  }

  @Specialization(guards = "isValue.executeEvaluated(argument)")
  public final SFarReference createActor(final Object receiver, final Object argument) {
    Actor actor = Actor.createActor();
    SFarReference ref = new SFarReference(actor, argument);

    assert argument instanceof SClass;
    SClass actorClass = (SClass) argument;

    if (VmSettings.ACTOR_TRACING) {
      ((TracingActor) actor).setActorType(actorClass.getName());
      ActorExecutionTrace.actorCreation(ref, sourceSection);
    }
    return ref;
  }

  @Specialization(guards = "!isValue.executeEvaluated(argument)")
  public final Object throwNotAValueException(final Object receiver, final Object argument) {
    return KernelObj.signalException("signalNotAValueWith:", argument);
  }
}

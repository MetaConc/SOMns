package som.primitives;

import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.source.SourceSection;

import som.interpreter.actors.Actor;
import som.interpreter.actors.Actor.ActorProcessingThread;
import som.interpreter.actors.EventualMessage;
import som.interpreter.actors.EventualMessage.PromiseMessage;
import som.interpreter.actors.SFarReference;
import som.interpreter.actors.SPromise;
import som.interpreter.actors.SPromise.SResolver;
import som.interpreter.nodes.nary.BinaryComplexOperation;
import som.interpreter.nodes.nary.UnaryBasicOperation;
import som.vm.VmSettings;
import som.vm.constants.Nil;
import som.vmobjects.SBlock;
import som.vmobjects.SSymbol;
import tools.concurrency.Assertion;
import tools.concurrency.Assertion.FutureAssertion;
import tools.concurrency.Assertion.GloballyAssertion;
import tools.concurrency.Assertion.NextAssertion;
import tools.concurrency.Assertion.UntilAssertion;

public class AssertionPrims {

  @GenerateNodeFactory
  @Primitive(primitive = "assertNext:")
  public abstract static class AssertNextPrim extends UnaryBasicOperation{

    protected AssertNextPrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doSBlock(final SBlock statement) {
      if (!VmSettings.ENABLE_ASSERTIONS) {
        return Nil.nilObject;
      }

      if (Thread.currentThread() instanceof ActorProcessingThread) {
        ActorProcessingThread apt = (ActorProcessingThread) Thread.currentThread();
        Actor a = apt.getCurrentlyExecutingActor();
        a.addAssertion(new NextAssertion(statement));
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }

      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "assertNow:")
  public abstract static class AssertNowPrim extends UnaryBasicOperation{

    protected AssertNowPrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doSBlock(final SBlock statement) {
      if (!VmSettings.ENABLE_ASSERTIONS) {
        return Nil.nilObject;
      }

      if (!(boolean) statement.getMethod().invoke(new Object[] {statement})) {
          throw new AssertionError();
      }

      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "assertFuture:")
  public abstract static class AssertFuturePrim extends UnaryBasicOperation{

    protected AssertFuturePrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doSBlock(final SBlock statement) {
      if (!VmSettings.ENABLE_ASSERTIONS) {
        return Nil.nilObject;
      }

      if (Thread.currentThread() instanceof ActorProcessingThread) {
        ActorProcessingThread apt = (ActorProcessingThread) Thread.currentThread();
        Actor a = apt.getCurrentlyExecutingActor();
        a.addAssertion(new FutureAssertion(statement));
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }

      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "assertGlobally:")
  public abstract static class AssertGloballyPrim extends UnaryBasicOperation{

    protected AssertGloballyPrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doSBlock(final SBlock statement) {
      if (!VmSettings.ENABLE_ASSERTIONS) {
        return Nil.nilObject;
      }

      if (Thread.currentThread() instanceof ActorProcessingThread) {
        ActorProcessingThread apt = (ActorProcessingThread) Thread.currentThread();
        Actor a = apt.getCurrentlyExecutingActor();
        a.addAssertion(new GloballyAssertion(statement));
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }

      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "assert:until:")
  public abstract static class AssertUntilPrim extends BinaryComplexOperation{

    protected AssertUntilPrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doSBlock(final SBlock statement, final SBlock until) {
      if (!VmSettings.ENABLE_ASSERTIONS) {
        return Nil.nilObject;
      }

      if (Thread.currentThread() instanceof ActorProcessingThread) {
        ActorProcessingThread apt = (ActorProcessingThread) Thread.currentThread();
        Actor a = apt.getCurrentlyExecutingActor();
        a.addAssertion(new UntilAssertion(statement, until));
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }

      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "assert:release:")
  public abstract static class AssertReleasePrim extends BinaryComplexOperation{

    protected AssertReleasePrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doSBlock(final SBlock statement, final SBlock release) {
      if (!VmSettings.ENABLE_ASSERTIONS) {
        return Nil.nilObject;
      }

      if (Thread.currentThread() instanceof ActorProcessingThread) {
        ActorProcessingThread apt = (ActorProcessingThread) Thread.currentThread();
        Actor a = apt.getCurrentlyExecutingActor();
        a.addAssertion(new Assertion.ReleaseAssertion(statement, release));
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }

      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "assertMessage:")
  public abstract static class AssertMessagePrim extends UnaryBasicOperation{

    protected AssertMessagePrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doSSymbol(final SSymbol selector) {
      if (Thread.currentThread() instanceof ActorProcessingThread) {
        return EventualMessage.getCurrentExecutingMessage().getSelector().equals(selector);

      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }
    }

    @Specialization
    public final Object doString(final String messageType) {
      if (Thread.currentThread() instanceof ActorProcessingThread) {
        return EventualMessage.getCurrentExecutingMessage().getSelector().getString().equals(messageType);
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "assertSender:")
  public abstract static class AssertSenderPrim extends UnaryBasicOperation{

    protected AssertSenderPrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doSSymbol(final SSymbol actorClass) {
      if (Thread.currentThread() instanceof ActorProcessingThread) {
        if (EventualMessage.getCurrentExecutingMessage().getSender().getActorType() == null) {
          return actorClass.toString().equals("#main");
        }
        return EventualMessage.getCurrentExecutingMessage().getSender().getActorType().equals(actorClass);
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }
    }

    @Specialization
    public final Object doReference(final SFarReference actor) {
      if (Thread.currentThread() instanceof ActorProcessingThread) {
        return actor.getActor().equals(EventualMessage.getCurrentExecutingMessage().getSender());
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }
    }

    @Specialization
    public final Object doString(final String actorType) {
      if (Thread.currentThread() instanceof ActorProcessingThread) {
        return EventualMessage.getCurrentExecutingMessage().getSender().getActorType().getString().equals(actorType);
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "assertPromiseComplete:")
  public abstract static class AssertPromiseResolvedPrim extends UnaryBasicOperation{

    protected AssertPromiseResolvedPrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doPromise(final SPromise p) {
      return p.isCompleted();
    }

    @Specialization
    public final Object doResolver(final SResolver r) {
      return r.getPromise().isCompleted();
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "assertPromiseMessage:")
  public abstract static class AssertPromiseMsgPrim extends UnaryBasicOperation{

    protected AssertPromiseMsgPrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object dovoid(final Object receiver) {
      if (Thread.currentThread() instanceof ActorProcessingThread) {
        return EventualMessage.getCurrentExecutingMessage() instanceof PromiseMessage;
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "onSend:do:")
  public abstract static class OnSendPrim extends BinaryComplexOperation{

    protected OnSendPrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doSBlock(final SSymbol message, final SBlock aBlock) {
      if (!VmSettings.ENABLE_ASSERTIONS) {
        return Nil.nilObject;
      }

      if (Thread.currentThread() instanceof ActorProcessingThread) {
        ActorProcessingThread apt = (ActorProcessingThread) Thread.currentThread();
        Actor a = apt.getCurrentlyExecutingActor();
        a.addSendHook(message, aBlock);
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }

      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "onReceive:do:")
  public abstract static class OnReceivePrim extends BinaryComplexOperation{

    protected OnReceivePrim(final boolean eagerlyWrapped, final SourceSection source) {
      super(eagerlyWrapped, source);
    }

    @Specialization
    public final Object doSBlock(final SSymbol message, final SBlock aBlock) {
      if (!VmSettings.ENABLE_ASSERTIONS) {
        return Nil.nilObject;
      }

      if (Thread.currentThread() instanceof ActorProcessingThread) {
        ActorProcessingThread apt = (ActorProcessingThread) Thread.currentThread();
        Actor a = apt.getCurrentlyExecutingActor();
        a.addReceiveHook(message, aBlock);
      } else {
        throw new java.lang.RuntimeException("Assertion only available when processing messages");
      }

      return Nil.nilObject;
    }
  }
}

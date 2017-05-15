package tools.concurrency;

import java.util.HashSet;
import java.util.Set;

import com.oracle.truffle.api.nodes.Node.Child;

import som.interpreter.actors.EventualMessage;
import som.interpreter.actors.ResolvePromiseNode;
import som.interpreter.actors.SPromise;
import som.interpreter.actors.SPromise.Resolution;
import som.interpreter.actors.SPromise.SResolver;
import som.interpreter.actors.WrapReferenceNode;
import som.interpreter.actors.WrapReferenceNodeGen;
import som.vmobjects.SBlock;
import tools.concurrency.TracingActors.TracingActor;

public class Assertion {
  SBlock statement;
  SResolver result;

  @Child protected WrapReferenceNode wrapper = WrapReferenceNodeGen.create();

  public Assertion(final SBlock statement, final SResolver result) {
    super();
    this.result = result;
    this.statement = statement;
  }

  public void evaluate(final TracingActor actor, final EventualMessage msg) {
    boolean result = (boolean) statement.getMethod().invoke(new Object[] {statement});
    if (!result) {
      throwError();
    }
  }

  protected void throwError() {
    ResolvePromiseNode.resolve(Resolution.SUCCESSFUL, wrapper,
        result.getPromise(), true,
        result.getPromise().getOwner(), false);
  }

  public static class UntilAssertion extends Assertion{
    SBlock until;

    public UntilAssertion(final SBlock statement, final SResolver result, final SBlock until) {
      super(statement, result);
      this.until = until;
    }

    @Override
    public void evaluate(final TracingActor actor, final EventualMessage msg) {
      boolean result = (boolean) until.getMethod().invoke(new Object[] {until});
      if (!result) {
        boolean result2 = (boolean) statement.getMethod().invoke(new Object[] {statement});
        if (!result2) {
          throwError();
        } else {
          actor.addAssertion(this);
        }
      }
    }
  }

  public static class ReleaseAssertion extends Assertion{
    SBlock release;

    public ReleaseAssertion(final SBlock statement, final SResolver result, final SBlock release) {
      super(statement, result);
      this.release = release;
    }

    @Override
    public void evaluate(final TracingActor actor, final EventualMessage msg) {
      boolean result = (boolean) release.getMethod().invoke(new Object[] {release});
      if (!result) {
        throwError();
      }

      boolean result2 = (boolean) statement.getMethod().invoke(new Object[] {statement});
      if (!result2) {
        actor.addAssertion(this);
      }
    }
  }

  public static class NextAssertion extends Assertion{

    public NextAssertion(final SBlock statement, final SResolver result) {
      super(statement, result);
    }
  }

  public static class FutureAssertion extends Assertion{
    protected static Set<FutureAssertion> futureAssertions = new HashSet<>();

    public FutureAssertion(final SBlock statement, final SResolver result) {
      super(statement, result);
      synchronized (futureAssertions) {
        futureAssertions.add(this);
      }
    }

    @Override
    public void evaluate(final TracingActor actor, final EventualMessage msg) {
      boolean result = (boolean) statement.getMethod().invoke(new Object[] {statement});
      if (result) {
        synchronized (futureAssertions) {
          futureAssertions.remove(this);
        }
      } else {
        actor.addAssertion(this);
      }
    }

    public void finalCheck() {
      boolean result = (boolean) statement.getMethod().invoke(new Object[] {statement});
      if (!result) {
        throwError();
      }
    }

    public static void checkFutureAssertions() {
      if (futureAssertions.size() > 0) {
        for (FutureAssertion fa: futureAssertions) {
          fa.finalCheck();
        }
      }
    }
  }

  public static class GloballyAssertion extends Assertion{

    public GloballyAssertion(final SBlock statement, final SResolver result) {
      super(statement, result);
    }

    @Override
    public void evaluate(final TracingActor actor, final EventualMessage msg) {
      boolean result = (boolean) statement.getMethod().invoke(new Object[] {statement});
      if (!result) {
        throwError();
      } else {
        actor.addAssertion(this);
      }
    }
  }

  public static class ResultUsedAssertion extends FutureAssertion{
    final SPromise checkedPromise;

    public ResultUsedAssertion(final SPromise statement, final SResolver result) {
      super(null, result);
      this.checkedPromise = statement;
    }

    @Override
    public void evaluate(final TracingActor actor, final EventualMessage msg) {
      synchronized (checkedPromise) {
        if (checkedPromise.isResultUsed()) {
          synchronized (futureAssertions) {
            futureAssertions.remove(this);
          }
        } else {
          actor.addAssertion(this);
        }
      }
    }

    @Override
    public void finalCheck() {
      synchronized (checkedPromise) {
        if (!checkedPromise.isResultUsed()) {
          throwError();
        }
      }
    }
  }
}

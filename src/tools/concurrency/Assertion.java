package tools.concurrency;

import java.util.HashSet;
import java.util.Set;

import com.oracle.truffle.api.nodes.IndirectCallNode;

import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.vmobjects.SBlock;

public class Assertion {
  SBlock statement;
  IndirectCallNode icn = IndirectCallNode.create();

  public Assertion(final SBlock statement) {
    super();
    this.statement = statement;
  }

  public void evaluate(final Actor actor, final EventualMessage msg) {
    boolean result = (boolean) statement.getMethod().invoke(new Object[] {statement});
    if (!result) {
      throw new AssertionError(statement.toString());
    }
  }


  public static class UntilAssertion extends Assertion{
    SBlock until;

    public UntilAssertion(final SBlock statement, final SBlock until) {
      super(statement);
      this.until = until;
    }

    @Override
    public void evaluate(final Actor actor, final EventualMessage msg) {
      boolean result = (boolean) until.getMethod().invoke(new Object[] {until});
      if (!result) {
        boolean result2 = (boolean) statement.getMethod().invoke(new Object[] {statement});
        if (!result2) {
          throw new AssertionError(statement.toString());
        } else {
          actor.addAssertion(this);
        }
      }
    }
  }

  public static class ReleaseAssertion extends Assertion{
    SBlock release;

    public ReleaseAssertion(final SBlock statement, final SBlock release) {
      super(statement);
      this.release = release;
    }

    @Override
    public void evaluate(final Actor actor, final EventualMessage msg) {
      boolean result = (boolean) release.getMethod().invoke(new Object[] {release});
      if (!result) {
        throw new AssertionError(statement.toString());
      }

      boolean result2 = (boolean) statement.getMethod().invoke(new Object[] {statement});
      if (!result2) {
        actor.addAssertion(this);
      }
    }
  }

  public static class NextAssertion extends Assertion{
    public NextAssertion(final SBlock statement) {
      super(statement);
    }
  }

  public static class FutureAssertion extends Assertion{
    private static Set<FutureAssertion> futureAssertions = new HashSet<>();

    public FutureAssertion(final SBlock statement) {
      super(statement);
      synchronized (futureAssertions) {
        futureAssertions.add(this);
      }
    }

    @Override
    public void evaluate(final Actor actor, final EventualMessage msg) {
      boolean result = (boolean) statement.getMethod().invoke(new Object[] {statement});
      if (result) {
        synchronized (futureAssertions) {
          futureAssertions.remove(this);
        }
      } else {
        actor.addAssertion(this);
      }
    }

    public static void checkFutureAssertions() {
      if (futureAssertions.size() > 0) {
        throw new AssertionError(futureAssertions.iterator().next().statement.toString());
      }
    }
  }

  public static class GloballyAssertion extends Assertion{
    public GloballyAssertion(final SBlock statement) {
      super(statement);
    }

    @Override
    public void evaluate(final Actor actor, final EventualMessage msg) {
      boolean result = (boolean) statement.getMethod().invoke(new Object[] {statement});
      if (!result) {
        throw new AssertionError(statement.toString());
      } else {
        actor.addAssertion(this);
      }
    }
  }

}

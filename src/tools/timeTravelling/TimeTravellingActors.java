package tools.timeTravelling;

import java.util.concurrent.ForkJoinPool;

import som.VM;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import tools.concurrency.TracingActors.TracingActor;


// A dummy class to serve as any actor not currently being replayed.
// This actor does not store any of the incoming messages;
public class TimeTravellingActors {
  public static class AbsorbingActor extends Actor {
    public AbsorbingActor(final VM vm) {
      super(vm);
    }

    // Since we don't actually want to execute the message do not store it
    @Override
    public synchronized void send(final EventualMessage msg,
        final ForkJoinPool actorPool) {
    }
  }

  public static class TimeTravelActor extends TracingActor {
    public TimeTravelActor(final VM vm) {
      super(vm);
    }

    public void setId(final long actorId) {
      this.actorId = actorId;
    }
  }
}

package tools.timeTravelling;

import java.util.concurrent.ForkJoinPool;

import som.VM;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;


// A dummy class to serve as any actor not currently being replayed.
// This actor does not store any of the incoming messages;
public final class AbsorbingActor extends Actor {

  public AbsorbingActor(final VM vm) {
    super(vm);
  }

  // Since we don't actually want to execute the message do not store it
  @Override
  public synchronized void send(final EventualMessage msg,
      final ForkJoinPool actorPool) {
  }
}

package tools.timeTravelling;

import static tools.timeTravelling.Database.getDatabaseInstance;

import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Transaction;

import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.vmobjects.SClass;
import som.vmobjects.SObject.SMutableObject;

//this class allows the state of a root node to be written and read to/from file or database
public  class ObjectWriter {

  public static synchronized void writeTargetArgument(final Long messageId, final EventualMessage msg, final Object t) {
    try {
      if (t instanceof SMutableObject) {
        // TODO ensure the platform is the only possible immutable top level object
        SMutableObject target = (SMutableObject) t;

        Database database = getDatabaseInstance();
        Session session = database.startSession();
        Transaction transaction = database.startTransaction(session);

        Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();

        if (!targetActor.inDatabase()) {
          database.createActor(transaction, targetActor);
          targetActor.addedToDatabase();
        }

        database.createCheckpoint(transaction, messageId, msg, targetActor.getId(), target);

        database.commitTransaction(transaction);
        database.endSession(session);
      } else if (t instanceof SClass) {
        // method is either a constructor or static method, no target object, only store arguments
        SClass target = (SClass) t;

        Database database = getDatabaseInstance();
        Session session = database.startSession();
        Transaction transaction = database.startTransaction(session);

        Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();

        if (!targetActor.inDatabase()) {
          database.createActor(transaction, targetActor);
          targetActor.addedToDatabase();
        }

        database.createConstructor(transaction, messageId, msg, targetActor.getId(), target);

        database.commitTransaction(transaction);
        database.endSession(session);

      } else {
        System.out.println("ignored: " + t.getClass() + " " + t.toString());
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}

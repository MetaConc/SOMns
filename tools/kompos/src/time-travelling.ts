import {dbgLog} from "./source"
import {Activity} from "./messages";
import {messageEvent} from "./protocol";

export class timeTravelling {
	static sessionId: number;

	static setSessionId(sessionId: number){
		timeTravelling.sessionId = sessionId;
		dbgLog("sessionId: " + sessionId);
	}

	static minimalReplay(_activity: Activity, _message: messageEvent){
		dbgLog("minimalReplay");
	}

	static fullReplay(_activity: Activity, _message: messageEvent){
		dbgLog("fullReplay");
	}
}
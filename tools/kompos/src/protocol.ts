import * as d3 from "d3";
import {Activity, IdMap} from "./messages";
import {HistoryData} from "./history-data"
//import {dbgLog} from "./source";

const actorStart = 20;      // height at which actor headings are created
const actorHeight = 30;     // height of actor headings
const actorWidth = 60;      // width of actor headings
const actorSpacing = 100;   // space between actors (width)

const turnRadius = 20;      // radius of turns
const turnSpacing = 50;     // space between consequent turns
const messageSpacing = 20;  // space between multiple message when enlarged

const noHighlightWidth = 2; // width of turn borders when not highlighted
const highlightedWidth = 5; // width of turn borders when highlighted
const opacity = 0.5;

var svgContainer; //global canvas, stores actor <g> elements
var color = ["#3366cc", "#dc3912", "#ff9900", "#109618", "#990099", "#0099c6", "#dd4477", "#66aa00", "#b82e2e", "#316395", "#994499", "#22aa99", "#aaaa11", "#6633cc", "#e67300", "#8b0707", "#651067", "#329262", "#5574a6", "#3b3eac"];

//actor each have their own svg groups
//one group for the heading: the square and the text field. 
//one group for all the other elements: turns and messages
//  to hide an actor the other group and all incoming messages are set to hidden
class ActorHeading {
  name:               string;
  x:                  number;
  y:                  number;
  color:              string;
  turnCount:          number;
  container:          d3.Selection<SVGElement>;
  turns:              TurnNode[];
  static actorCount:  number = 0;
  visibility:         boolean;
  activity:           Activity;  

  constructor(activity: Activity) {
    this.name = activity.name;
    this.color = color[ActorHeading.actorCount % color.length];
    this.x = 50+ActorHeading.actorCount++*actorSpacing;
    this.y = actorStart;
    this.turnCount = 0;
    this.visibility = true;
    this.turns = [];
    this.activity = activity;
    drawActor(this);
  }

  addTurn(turn: TurnNode) {
    this.turns.push(turn);
    return ++this.turnCount;
  }

  //if no turn was created create turn without origin
  getLastTurn() {
    if(this.turns.length === 0) {
      return (new TurnNode(this, new EmptyMessage()));
    } else {
      return this.turns[this.turns.length - 1];
    }
  }

  changeVisibility() {
    this.visibility = !this.visibility;
    if(this.visibility){
      this.container.style("visibility", "inherit");
    } else {
      this.container.style("visibility", "hidden");
    }
    for (const turn of this.turns) {
      turn.changeVisibility(this.visibility);
    }
  }

  getColor() {
    return this.color;
  }

  getContainer() {
    return this.container;
  }

  transpose(threshold, yShift) {
    for (var i = threshold; i < this.turns.length; i++) {
      this.turns[i].performTranspose(yShift);
    }
  }
}

class TurnNode {
  count:          number;
  x:              number;
  y:              number;
  incoming:       EmptyMessage;
  outgoing:       Message[];
  actor:          ActorHeading;
  visualization:  d3.Selection<SVGElement>;

  constructor(actor: ActorHeading, message: EmptyMessage) {
    this.count = actor.addTurn(this);
    this.actor = actor;
    this.x = actor.x + (actorWidth / 2);
    this.y = actorStart + actorHeight + this.count * turnSpacing;
    this.incoming = message; //possible no message
    this.outgoing = [];
    this.visualization = drawTurn(this);
  }

  highlightOn() {
    this.visualization.style("stroke-width", highlightedWidth)
                      .style("stroke", "black");
    this.incoming.highlightOn();
  }

  highlightOff() {
    this.visualization.style("stroke-width", noHighlightWidth)
                      .style("stroke", this.getColor());
    this.incoming.highlightOff();
  }

  //the turn itself is made invisible by the group, only the incoming arc needs to be explicitly made invisible
  changeVisibility(visible: boolean) {
    this.incoming.changeVisibility(visible);
  }

  getColor() {
    return this.actor.getColor();
  }

  getText() {
    return this.incoming.getText();
  }

  getContainer() {
    return this.actor.getContainer();
  }

  addMessage(message: Message) {
    this.outgoing.push(message);
    return this.outgoing.length;
  }

  getMessageCount() {
    return this.outgoing.length;
  }

  enlarge(yShift: number) {
    this.actor.transpose(this.count, yShift);
    for (const message of this.outgoing) {
      message.enlarge();
    }
  }

  shrink() {
    this.actor.transpose(this.count, 0);
    for (const message of this.outgoing) {
      message.shiftAtSender(0);
    }
  }

  performTranspose(yShift: number) {
    transposeTurn(this.visualization, yShift);
    this.incoming.shiftAtTarget(yShift);
    for (const message of this.outgoing) {
      message.shiftAtSender(yShift);
    }
  }
}

class EmptyMessage {
  constructor (){};
  highlightOn(){};
  highlightOff(){};
  changeVisibility(_visible: boolean){};
  getText(){return "42"};
  shiftAtSender(_yShift: number){};
  shiftAtTarget(_yShift: number){};
}

class Message extends EmptyMessage {
  sender:        TurnNode;
  target:        TurnNode;
  senderShift:   number;
  targetShift:   number;
  text:          string; 
  order:         number;  // indicates order of message sends inside turn
  visualization: d3.Selection<SVGElement>;
  messageToSelf: boolean;

  getSender(){
    return this.sender;
  }

  getTarget(){
    return this.target;
  }

  constructor(senderActor: ActorHeading, targetActor: ActorHeading, text: string) {
    super();
    this.text = text;
    this.sender = senderActor.getLastTurn();
    this.senderShift = 0;
    this.order = this.sender.addMessage(this);
    this.target = new TurnNode(targetActor, this);
    this.targetShift = 0; 
    this.messageToSelf = senderActor === targetActor;
    this.draw(); 
  }

  private draw(){
    if(this.messageToSelf){
      this.visualization = drawMessageToSelf(this);
    } else {  
      this.visualization = drawMessage(this);
    }
  }

  private redraw(){
    this.visualization.remove();
    this.draw();
  }

  highlightOn() {
    this.visualization.style("stroke-width", highlightedWidth);
    this.sender.highlightOn();
  }

  highlightOff() {
    this.visualization.style("stroke-width", 1);
    this.sender.highlightOff();
  }

  changeVisibility(visible: boolean) {
    if(visible){
      this.visualization.style("visibility", "inherit");
    } else {
      this.visualization.style("visibility", "hidden");
    }
  }

  getText() {
    return this.text;
  }

  enlarge() {
    this.senderShift = this.order * messageSpacing;
    this.redraw();
  }

  shiftAtSender(yShift: number){
    this.senderShift = yShift;
    this.redraw();
  }

  shiftAtTarget(yShift: number) {
    this.targetShift = yShift;
    this.redraw();
  }
}

export class ProtocolOverview {
  private actors: IdMap<ActorHeading>;
  private data: HistoryData;
  private static highlighted: TurnNode;

  public newActivities(newActivities: Activity[]) {
    for(const act of newActivities){
      if(act.type === "Actor"){
        var actor = new ActorHeading(act);
        this.actors[act.id] = actor;
      }
    }
  }

  public newMessages(newMessages: [number, number, number][]) {
    for(const [senderId, targetId, messageId] of newMessages){
      var senderActor = this.actors[senderId];
      var targetActor = this.actors[targetId];
      var message = this.data.getName(messageId);
      new Message(senderActor, targetActor, message);
    }
  }

  public constructor(data: HistoryData) {
    displayProtocolOverview();
    ActorHeading.actorCount = 0;
    this.actors = {};
    this.data = data;
  }

  //ensure only one node chain can be highlighted at the same time
  static changeHighlight(turn: TurnNode, visualization: d3.Selection<SVGElement>) {
    if(ProtocolOverview.highlighted){
      ProtocolOverview.highlighted.highlightOff();
      shrinkTurn(turn, visualization);
    }
    if(turn == ProtocolOverview.highlighted){
      ProtocolOverview.highlighted = null;
    } else {
      turn.highlightOn();
      enlargeTurn(turn, visualization);
      ProtocolOverview.highlighted = turn;
    }
  }
}

function displayProtocolOverview() {
  const canvas = $("#protocol-canvas");
  canvas.empty(); // after every restart the canvas needs to be redrawn in case a different program is running on the backend

  svgContainer = d3.select("#protocol-canvas")
    .append("svg")
    .attr("width", 1000)
    .attr("height", 1000)
    .attr("style", "background: none;");
}

function drawActor(actor: ActorHeading){
  var actorHeading = svgContainer.append("g");
  var actorGroup = svgContainer.append("g");
  actor.container = actorGroup;
  
  actorHeading.append("text")
    .attr("x", actor.x+actorWidth/2)
    .attr("y", actor.y+actorHeight/2)
    .attr("font-size","20px")
    .attr("text-anchor", "middle")
    .text(actor.name);    

  actorHeading.append("rect")
    .attr("x", actor.x)
    .attr("y", actor.y)
    .attr("rx", 5)
    .attr("height", actorHeight)
    .attr("width", actorWidth)
    .style("fill", actor.color)
    .style("stroke", actor.color)
    .style("opacity", opacity)
    .on("click", function(){
      actor.changeVisibility();
    });
}

function drawTurn(turn: TurnNode) {
  var text = turn.getContainer().append("text")
    .attr("x", turn.x)
    .attr("y", turn.y)
    .attr("font-size","20px")
    .attr("text-anchor", "middle")
    .style("opacity", 0)
    .text(turn.getText());   

  var visualization = turn.getContainer().append("ellipse")
      .attr("cx", turn.x)
      .attr("cy", turn.y)
      .attr("rx", turnRadius)
      .attr("ry", turnRadius)
      .style("fill", turn.getColor())
      .style("opacity", opacity)
      .style("stroke-width", noHighlightWidth)
      .style("stroke", turn.getColor())
      .on("click", function(){
        ProtocolOverview.changeHighlight(turn, d3.select(this));
      })
      .on("mouseover", function(){
        text.style("opacity", 1);
      })
      .on("mouseout", function(){
        text.style("opacity", 0);
      });
  return visualization;
}

function enlargeTurn(turn: TurnNode, visualization: d3.Selection<SVGElement>) {
  var growSize = turn.getMessageCount() * messageSpacing;
  turn.enlarge(growSize);
  visualization.attr("ry", turnRadius + growSize / 2);
  visualization.attr("cy", turn.y + growSize / 2);
}

function shrinkTurn(turn: TurnNode, visualization: d3.Selection<SVGElement>) {
  turn.shrink();
  visualization.attr("ry", turnRadius);
  visualization.attr("cy", turn.y);
}

function transposeTurn(visualization: d3.Selection<SVGElement>, yShift: number){
  visualization.attr("transform", "translate(0," + yShift + ")")
}

function drawMessage(message: Message){
  var sender = message.sender;
  var target = message.target;
  var visualization = sender.getContainer().append("line")
    .attr("x1", sender.x)
    .attr("y1", sender.y + message.senderShift)
    .attr("x2", target.x)
    .attr("y2", target.y + message.targetShift)
    .style("stroke", sender.getColor());
  return visualization;
}

function drawMessageToSelf(message: Message){
  var sender = message.sender;
  var target = message.target;
 
  var lineData: [number, number][] = [
    [ sender.x , sender.y + message.senderShift],
    [ sender.x+turnRadius*1.5 , sender.y + message.senderShift],
    [ target.x+turnRadius*1.5 , target.y + message.targetShift], 
    [ target.x , target.y + message.targetShift]];
  var lineGenerator: any = 
      d3.svg.line()
        .x(function(d) { return d[0]; })
        .y(function(d) { return d[1]; })
        .interpolate("linear");
  var visualization = sender.getContainer().append("path")
    .attr("d", lineGenerator(lineData))
    .style("fill", "none")
    .style("stroke", sender.getColor());
  return visualization;
}
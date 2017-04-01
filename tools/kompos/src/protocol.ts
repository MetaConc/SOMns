import * as d3 from "d3";
import {Activity, IdMap} from "./messages";
import {HistoryData} from "./history-data"
import {dbgLog} from "./source";

const actorStart = 20;      // height at which actor headings are created
const actorHeight = 30;     // height of actor headings
const actorWidth = 60;      // width of actor headings
const actorSpacing = 100;   // space between actors (width)

const turnRadius = 20;      // radius of turns
const turnSpacing = 50;     // space between consequent turns
const messageSpacing = 20;  // space between multiple message when enlarged
const markerSize = 7;       // size of markers (arrows and squares of enlarged messages)

const noHighlightWidth = 2; // width of turn borders when not highlighted
const highlightedWidth = 5; // width of turn borders when highlighted
const opacity = 0.5;

var svgContainer; // global canvas, stores actor <g> elements
var defs;         // global container to store all markers

var color = ["#3366cc", "#dc3912", "#ff9900", "#109618", "#990099", "#0099c6", "#dd4477", "#66aa00", "#b82e2e", "#316395", "#994499", "#22aa99", "#aaaa11", "#6633cc", "#e67300", "#8b0707", "#651067", "#329262", "#5574a6", "#3b3eac"];

// each actor has their own svg group.
// one heading group: the square, the text field and the other group. 
// one collection group: turns and messages
//   to hide an actor the other group and all incoming messages are set to hidden
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

  // add new turn to the actor, increases turnCount and adds turn to list of turns
  addTurn(turn: TurnNode) {
    this.turns.push(turn);
    return ++this.turnCount;
  }

  // if no turn was created create turn without origin
  getLastTurn() {
    if(this.turns.length === 0) {
      return (new TurnNode(this, new EmptyMessage()));
    } else {
      return this.turns[this.turns.length - 1];
    }
  }

  // set the visibility of the collection group, this hiddes all turns and messages outgoing from these turns.
  // afterwards set the visibility of each incoming message individually
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

  // a turn in this actor is enlarged. Move all other turns below that turn, downwards with the given yShift
  transpose(threshold, yShift) {
    for (var i = threshold; i < this.turns.length; i++) {
      this.turns[i].transpose(yShift);
    }
  }

  getColor() {
    return this.color;
  }

  getContainer() {
    return this.container;
  }
}

// A turn happens every time an actor processes a message.
// Turns store their incoming message and each outgoing message
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

  addMessage(message: Message) {
    this.outgoing.push(message);
    return this.outgoing.length;
  }

  // highlight this turn. 
  //  make the circle border bigger and black
  //  highlight the incoming message
  highlightOn() {
    this.visualization.style("stroke-width", highlightedWidth)
                      .style("stroke", "black");
    this.incoming.highlightOn();
  }

  // remove highlight from this turn
  //   make the circle border regular and color based on actor
  //   remove the highlight from the incoming message
  highlightOff() {
    this.visualization.style("stroke-width", noHighlightWidth)
                      .style("stroke", this.getColor());
    this.incoming.highlightOff();
  }

  // the turn itself is made invisible by the group, only the incoming arc needs to be made invisible
  changeVisibility(visible: boolean) {
    this.incoming.changeVisibility(visible);
  }

  // enlarge this turn
  //   every message receives own exit point from this turn
  //   shift other turns downwards to prevent overlap
  enlarge(yShift: number) {
    this.actor.transpose(this.count, yShift);
    for (const message of this.outgoing) {
      message.enlarge();
    }
  }

  // shrink this turn
  //   every message starts from the center of the node
  shrink() {
    this.actor.transpose(this.count, 0);
    for (const message of this.outgoing) {
      message.shrink();
    }
  }

  // move this turn with the give yShift vertically
  transpose(yShift: number) {
    transposeTurn(this.visualization, yShift);
    this.incoming.shiftAtTarget(yShift);
    for (const message of this.outgoing) {
      message.shiftAtSender(yShift);
    }
  }

  getColor() {
    return this.actor.getColor();
  }

  getContainer() {
    return this.actor.getContainer();
  }

  getMessageCount() {
    return this.outgoing.length;
  }

  getText() {
    return this.incoming.getText();
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

// message represent a message send between two actors
// messages go from a turn to another turn
// normally a message goes from the center of a turn to the center of a turn. 
// this can change if the turns shift or are enlarged
// when undoing a shift the original shift is unknown, so we shift back to the old position
// message can be shifted at both sender and receiver 
class Message extends EmptyMessage {
  static messageCount:  number = 0;
  id:            number;
  sender:        TurnNode;
  target:        TurnNode;
  senderShift:   number;  
  targetShift:   number;
  text:          string; 
  order:         number;  // indicates order of message sends inside turn
  visualization: d3.Selection<SVGElement>;
  visibility:    string;
  messageToSelf: boolean; // is both the sender and receiver the same object

  constructor(senderActor: ActorHeading, targetActor: ActorHeading, text: string) {
    super();
    this.id = Message.messageCount++;
    this.text = text;
    this.sender = senderActor.getLastTurn();
    this.senderShift = 0;
    this.order = this.sender.addMessage(this);
    this.target = new TurnNode(targetActor, this);
    this.targetShift = 0; 
    this.messageToSelf = senderActor === targetActor;
    this.visibility = "inherit";
    this.draw(false); 
  }

  private draw(createAncors: boolean){
    if(this.messageToSelf){
        this.visualization = drawMessageToSelf(this, createAncors, this.visibility);
      } else {  
        this.visualization = drawMessage(this, createAncors, this.visibility);
      }
  }

  // remove the visualization and create a new one
  // if the ancor where not defined yet the remove doesn't do anything
  private redraw(createAncors: boolean){
    d3.select("#endMarker"+this.id).remove();
    this.visualization.remove();
    this.draw(createAncors);
  }

  highlightOn() {
    this.visualization.style("stroke-width", highlightedWidth);
    this.sender.highlightOn();
  }

  highlightOff() {
    this.visualization.style("stroke-width", 1);
    this.sender.highlightOff();
  }

  // standard visibility is inherit
  // this allows message going from and to a hidden turn to stay hidden if either party becomes visible
  changeVisibility(visible: boolean) {
    if(visible){
      this.visibility = "inherit"
    } else {
      this.visibility = "hidden";
    }
    this.visualization.style("visibility", this.visibility);
  }

  enlarge() {
    this.senderShift = this.order * messageSpacing;
    this.redraw(true);
  }

  shrink(){
    d3.select("#startMarker"+this.id).remove();
    this.senderShift = 0;
    this.redraw(false);
  }

  shiftAtSender(yShift: number){
    this.senderShift = yShift;
    this.redraw(false);
  }

  shiftAtTarget(yShift: number) {
    this.targetShift = yShift;
    this.redraw(false);
  }

  getSender(){
    return this.sender;
  }

  getText() {
    return this.text;
  }

  getTarget(){
    return this.target;
  }
}

// this is the main class of the file, it stores all actors currently in use
// only one turn can be highlighted at a time
export class ProtocolOverview {
  private actors:                   IdMap<ActorHeading>;
  private data:                     HistoryData;
  private static highlighted:       TurnNode;
  
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

  // ensure only one node chain can be highlighted at the same time
  static changeHighlight(turn: TurnNode) {
    if(ProtocolOverview.highlighted){
      ProtocolOverview.highlighted.highlightOff();
      shrinkTurn(ProtocolOverview.highlighted, ProtocolOverview.highlighted.visualization);
    }
    if(turn == ProtocolOverview.highlighted){
      ProtocolOverview.highlighted = null;
    } else {
      turn.highlightOn();
      enlargeTurn(turn, turn.visualization);
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

  defs = svgContainer.append("defs");
}

//------------------------------------------------------------------------
//-                            visualizations                            -
//------------------------------------------------------------------------


function drawActor(actor: ActorHeading){
  var actorHeading = svgContainer.append("g");
  var actorGroup = actorHeading.append("g");
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
        ProtocolOverview.changeHighlight(turn);
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

function drawMessage(message: Message, createAncors: boolean, visibility: string){
  var sender = message.sender;
  var target = message.target;
  createMessageArrow(sender.getColor(), message.id);
  if(createAncors){
    createMessageAncor(sender.getColor(), message.id);
  }
  var visualization = sender.getContainer().append("line")
    .attr("x1", sender.x)
    .attr("y1", sender.y + message.senderShift)
    .attr("x2", target.x)
    .attr("y2", target.y + message.targetShift)
    .attr("marker-end", "url(#endMarker"+message.id+")")
    .attr("marker-start", "url(#startMarker"+message.id+")")
    .style("stroke", sender.getColor())
    .style("visibility", visibility);
  return visualization;
}
const lineGenerator: any = 
  d3.svg.line()
    .x(function(d) { return d[0]; })
    .y(function(d) { return d[1]; })
    .interpolate("linear");

function drawMessageToSelf(message: Message, createAncors: boolean, visibility: string){
  var sender = message.sender;
  var target = message.target;
  createMessageArrow(sender.getColor(), message.id);
  if(createAncors){
    createMessageAncor(sender.getColor(), message.id);
  }
    
  var lineData: [number, number][] = [
    [ sender.x , sender.y + message.senderShift],
    [ sender.x+turnRadius*1.5 , sender.y + message.senderShift],
    [ target.x+turnRadius*1.5 , target.y + message.targetShift], 
    [ target.x , target.y + message.targetShift]];
  var visualization = sender.getContainer().append("path")
    .attr("d", lineGenerator(lineData))
    .attr("marker-end", "url(#endMarker"+message.id+")")
    .attr("marker-start", "url(#startMarker"+message.id+")")
    .style("fill", "none")
    .style("stroke", sender.getColor())
    .style("visibility", visibility);
  return visualization;
}

function createMessageArrow(color: string, id: number){
  var lineData: [number, number][] = 
    [[0, 0],
     [markerSize, markerSize/2],
     [0, markerSize]];
  defs.append("marker")
    .attr("id", "endMarker"+id)
    .attr("refX", markerSize+turnRadius) // shift allong path (place arrow on path outside turn)
    .attr("refY", markerSize/2) // shift ortogonal of path (place arrow on middle of path)
    .attr("markerWidth", markerSize)
    .attr("markerHeight", markerSize)
    .attr("orient", "auto")
    .style("fill", color)
    .append("path")
    .attr("d", lineGenerator(lineData))
    .attr("class","arrowHead");
}

function createMessageAncor(color: string, id: number){
  var lineData: [number, number][] = 
    [[0, 0],
     [0, markerSize],
     [markerSize, markerSize],
     [markerSize, 0]];
  defs.append("marker")
    .attr("id", "startMarker"+id)
    .attr("refX", 0) 
    .attr("refY", 0)
    .attr("markerWidth", markerSize)
    .attr("markerHeight", markerSize)
    .style("fill", color)
    .append("path")
    .attr("d", lineGenerator(lineData))
    .on("click", function(){
      dbgLog("clicked marker");
    });
}
#
# The class-like object capsuling the visualization and all related activities.
# The design follows http://bost.ocks.org/mike/chart/.
#

NetworksOfNames = () ->
  
  # CONSTANTS
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  width = 960                               # visualization width
  height = 800                              # visualization height
  minStrokeWidth = 2                        # minimum stroke width (currently the actual width, because stroke do not vary)
  strokeColor = "#999"                      # normal stroke color
  strokeColorHigh = "#000"                  # highlighted stroke color
  personColor = "#fa7"                      # color for nodes representing people
  organizationColor = "#7af"                # color for nodes representing organizations
  personHColor = "#f77"                     # color for focal nodes when visualization appears (to allow quick spotting)
  organizationHColor = "#f77"               # color for organizations nodes when visualization appears (to allow quick spotting)
  normalFont = "14px Sans-serif, Arial"     # font for names and labels
  smallFont = "10px Sans-serif, Arial"      # font for neighbour indicators
  
  
  
  # FIELDS
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  svg = null                                # ths SVG
  vis = null                                # the visualization contained in the SVG
  dom = "body"                              # the DOM element the visualization is appended to
  data = null                               # input data
  nodesToHighlight = []                     # nodes to highlight (if any)
  nodes = null                              # nodes in the graph; set on update
  links = null                              # links in the graph; set on update
  paths = null                              # links in the graph; set on update
  labels = null                             # labels on links; set on update
  removedNodes = []                         # list of node ids that have been removed by the user (to prevent auto-re-expansion)
  removedLinks = []                         # list of link ids that have been removed by the user (to prevent auto-re-expansion)
  nodeShapes = null                         # node background shapes
  nodesMap = d3.map()                       # mapping of id to nodes
  linksMap = d3.map()                       # mapping of id to link
  linked = {}                               # hashmap that contains [id1,id2] oder[id2,id2] iff id1 and id2 are linked
  force = d3.layout.force()                 # force algorithm configuration
    .linkDistance(300)                      # see https://github.com/mbostock/d3/wiki/Force-Layout for details on parameters
    .linkStrength(1)
    .friction(0.9)
    .charge(-1000)
    .theta(0.8)
    .gravity(0.1)
    .size([width, height])
  layoutPaused = false                      # keeps track of whether layout should be done
  dragInitiated = false                     # set to true when user initiates drag gesture and to false when gesture ends
  maxLinkFreq = 0                           # holds the maximum link frequency in the current subgraph
  timeout = null                            # holds the timeout of user hovering
  
  
  
  # MOUSE HANDLERS
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  nodeClickHandler = null
  nodeContextmenuHandler = null
  linkClickHandler = null
  linkContextmenuHandler = null
  labelClickHandler = null
  labelContextmenuHandler = null
  
  
  # PRIVATE FUNCTIONS
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  dynamicLinkWidth = (l) -> minStrokeWidth
  dynamicLinkOpacity = (l) -> Math.max(l.significance, 0.3)
  
  # Data processing
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  preprocess = (data) ->
    # D3 uses the the object indices in array instead of their actual ids;
    # create maps of nodes and links by id
    data.nodes.forEach (n) ->
      n.numShown = 0
      nodesMap.set(n.id, n)
    
    data.links.forEach (l) ->
      linksMap.set(l.id, l)
    
    # Set node attributes that are required for the visualization
    data.nodes.forEach (n) ->
      if(!n.preprocessed)
        # project node name onto (x,y) in the visualization
        nameHash = Math.abs(n.name.hashCode())
        initialX = Math.floor(((nameHash & 0x0000FFFF) / 0xFFFF) * width)
        initialY = Math.floor(((nameHash >> 16) / 0xFFFF) * height)
        n.x = initialX
        n.y = initialY
        
        # calculate name widths on screen and set node width attribute
        d = n.name.dimensions(normalFont)
        n.width = d[0] + 10
        n.height = d[1]
        
        n.preprocessed = true
    
    # Set attributes of links and attributes depending on links.
    data.links.forEach (l) ->
      if (!l.preprocessed)
        # Count how many neighbours of every node has.
        nodesMap.get(l.source).numShown = nodesMap.get(l.source).numShown + 1
        nodesMap.get(l.target).numShown = nodesMap.get(l.target).numShown + 1
        
        # Replace source and target attributes (represented by ids) by the actual objects. 
        l.source = nodesMap.get(l.source)
        l.target = nodesMap.get(l.target)
        
        # Write neighboring relationship into a hashmap for fast retrieval.  
        linked["#{l.source.id},#{l.target.id}"] = true
        maxLinkFreq = Math.max(maxLinkFreq, l.freq)
        
        l.preprocessed = true
  
  # Update for the visualization (initially and if changes occur)
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  update = (data) ->
    # Create DOM elements for all links in the #links group element.
    ## Set the data.
    nlinks = vis.select("#links").selectAll(".link")
      .data(data.links, (l) -> l.id)
    
    ## Append one group element per link.
    linkGroups = nlinks.enter().append("g")
      .attr("class", "link")
    
    ## Add path.
    linkGroups.append("path")
      .attr("id", (l) -> "path" + l.id)
      .attr("class", "link-path")
      .style("stroke", strokeColor)
      .style("stroke-width", dynamicLinkWidth)
      .style("fill", "none")
      .attr("opacity", dynamicLinkOpacity)
      .attr("d", (d) ->
        dx = d.target.x - d.source.x
        dy = d.target.y - d.source.y
        dr = Math.sqrt(dx * dx + dy * dy)
        "M" + d.source.x + "," + d.source.y + "A" + dr + "," + dr + " 0 0,1 " + d.target.x + "," + d.target.y)
    
    ## Add label.
    linkGroups
      .append("text")
        .style("text-anchor", "middle")
        .attr("dy", -2)
      .append("textPath")
        .attr("id", (l) -> "label" + l.id)
        .attr("class", "link-label")
        .attr("opacity", dynamicLinkOpacity)
        .attr("startOffset", "50%")
        .attr("xlink:href", (l) -> "#path" + l.id)
        .style("font", normalFont)
        .text((l) -> if (l.tags.length > 0) then l.tags[0].label else "")
    
    ## Remove deleted links.
    nlinks.exit().remove()
    
    # Create DOM elements for all nodes in the #nodes group element.
    ## Set the data.
    nnodes = vis.select("#nodes").selectAll(".node")
      .data(data.nodes, (n) -> n.id)
    
    ## Add one group element per node.
    nodesG = nnodes.enter().append("g")
      .attr("id", (n) -> "node#{n.id}")
      .attr("class", "node")
      .attr("transform", (d) -> "translate(" + d.x + "," + d.y + ")")
      .attr("opacity", "1")
      .call(dragBehavior)
    
    ## Add the background elements.
    countWidth = (n) ->
      ("" + (n.numNeighbors - n.numShown)).dimensions(smallFont)[0] + 4
    
    ##- For the neighbour count.
    nodesG
      .append("rect")
        .attr("class", "node-bg")
        .attr("x", (n) -> n.width/2 - countWidth(n) - 1)
        .attr("y", (n) -> n.height/2 - 10)
        .attr("rx", 3)
        .attr("ry", 3)
        .attr("height", 20)
        .attr("width", (n) -> countWidth(n))
        .style("fill", (n) -> colorByType(n))
    
    ##- For the name.
    nodesG
      .append("rect")
        .attr("class", "node-bg")
        .attr("x", (n) -> -Math.floor(n.width / 2))
        .attr("y", (n) -> -Math.floor(n.height / 2))
        .attr("rx", 5)
        .attr("ry", 5)
        .attr("height", (n) -> n.height)
        .attr("width", (n) -> n.width)
        .style("fill", colorByType)
    
    ##- Shape to cover up the stroke of the main background when nodes are highlighted.
    nodesG
      .append("rect")
        .attr("class", "node-bg no-stroke")
        .attr("x", (n) -> n.width/2 - countWidth(n))
        .attr("y", (n) -> n.height/2 - 10)
        .attr("rx", 3)
        .attr("ry", 3)
        .attr("height", 20 - minStrokeWidth)
        .attr("width", (n) -> countWidth(n) - minStrokeWidth)
        .style("fill", (n) -> colorByType(n))
    
    # Highlight focus nodes and paint the rest normally.
    nodeShapes = nnodes.selectAll(".node-bg")   
    nodeShapes
      .style("fill", (n) ->
        if ($.inArray(n, nodesToHighlight) != -1) then hColorByType(n) else colorByType(n))
      .transition().duration(3000).style("fill", colorByType)
    nodesToHighlight = []
    
    # The entity name text.
    nodesG
      .append("text")
        .style("text-anchor", "middle")
        .style("font", normalFont)
        .attr("dy", 5) # simulates '.style("dominant-baseline", "center")' which is not implemented everywhere
        .text((n) -> n.name)
    
    # The neighbor count text.
    nodesG
      .append("text")
        .attr("class", "node-ncount")
        .attr("x", (n) -> n.width/2 - 4) # position right minus padding
        .attr("y", (n) -> n.height - 4)
        .style("font", smallFont)
        .style("text-anchor", "end")
        .text((n) -> (n.numNeighbors - neighborCount(n)))
    
    # Remove deleted nodes.
    nnodes.exit().remove()
    
    # Select nodes, links, link paths, link labels, and neighbour counts for convenient access.
    nodes = vis.selectAll(".node")
    links = vis.selectAll(".link")
    paths = vis.selectAll(".link-path")
    labels = vis.selectAll(".link-label")
    counts = vis.selectAll(".node-ncount")
    
    # Set text counts so that existing (not only entering) nodes get the right count.
    counts.text((n) ->
      (n.numNeighbors - neighborCount(n)))
    
    # Set mouse handlers on all elements.
    updateMouseHandlers()
    
    # Set hovering behaviour for all elements.
    nodes.each (d, i) ->
      $(this).hoverIntent {
        over: (-> highlightNode(d, i)), 
        out: (-> unhighlightNode(d, i)),
        sensitivity: 3 
      }
    
    paths.each (l, i) ->
      $(this).hoverIntent {
        over: (-> highlightLink(l, i)),
        out:  (-> unhighlightLink(l, i)),
        sensitivity: 3
      }
      
    labels.each (l, i) ->
      $(this).hoverIntent {
        over: (-> highlightLink(l, i)),
        out:  (-> unhighlightLink(l, i)),
        sensitivity: 3
      }
    
    # Start layouting (for entering nodes; existing nodes are fixed) and fix them after some time.
    # TODO fixed time by checking layout stability. Currently the final layout depends on machine performance.
    resumeLayout()
    setTimeout fixAllNodes, 2000
  
  # Set mousehandlers (if specified) on the respective elements.
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  updateMouseHandlers = () ->
    # NODES
    nodes.on "click", (n) ->
      if (nodeClickHandler)
        nodeClickHandler(n, d3.event.layerX, d3.event.layerY)
  
    nodes.on "contextmenu", (n) ->
      if (nodeContextmenuHandler)
        nodeContextmenuHandler(n, d3.event.layerX, d3.event.layerY)
      d3.event.preventDefault()
    
    # LINKS
    paths.on "click", (l) ->
      if (linkClickHandler)
        linkClickHandler(l, d3.event.layerX, d3.event.layerY)
  
    paths.on "contextmenu", (l) ->
      if (linkContextmenuHandler)
        linkContextmenuHandler(l, d3.event.layerX, d3.event.layerY)
      d3.event.preventDefault()
    
    # LABELS
    labels.on "click", (t) ->
      if (labelClickHandler)
        labelClickHandler(t, d3.event.layerX, d3.event.layerY)
  
    labels.on "contextmenu", (t) ->
      if (labelContextmenuHandler)
        labelContextmenuHandler(t, d3.event.layerX, d3.event.layerY)
      d3.event.preventDefault()
  
  # Determine whether n1 and n2 are neighbours.
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  neighboring = (n1, n2) ->
    linked["#{n1.id},#{n2.id}"] or linked["#{n2.id},#{n1.id}"]
  
  # Determine node color by its type.
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  colorByType = (n) -> if n.type == 1 then personColor else organizationColor
  hColorByType = (n) -> if n.type == 1 then personHColor else organizationHColor
  
  # Determine neighbour count.
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  neighborCount = (n) ->
    i = 0
    data.nodes.forEach (m) ->
      if (neighboring(n, m))
        i = i + 1
    i
  
  # Layout behaviour: Dragging, dropping, node fixing.
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  dragBehavior = d3.behavior.drag()
    .on "dragstart", (d, i) ->
      # raise dragged element to the top
      $(this).parent().append(this)
      # start dragging if left mousebutton is used
      if (d3.event.sourceEvent.which == 1)
        dragInitiated = true
        force.stop() if not layoutPaused
    .on "drag", (d, i) ->
      if (dragInitiated)
        # update node location to mouse location
        d.px += d3.event.dx
        d.py += d3.event.dy
        d.x += d3.event.dx
        d.y += d3.event.dy
        tick()
        checkDropOn(d)
    .on "dragend", (d, i) ->
      if (d3.event.sourceEvent.which == 1)
        # revert dragging state, fix node
        force.resume() if not layoutPaused
        d.fixed = true
        tick()
        dragInitiated = false
  
  # Sample implementation of a user dropping a node on (or near) another node. Not yet used.
  checkDropOn = (d) ->
    dropOn = data.nodes.filter (n) ->
      n != d && distance(n, d) < 10
    # if (dropOn.length == 1) then ...
      # User would be dropping the dragged node onto one specific element.
      # Merge not implemented because the results are too far-reaching.
      # Node ids could be identified with other objects on the client, but
      # sources, tagging and patterns would need to take resulting synonyms
      # into account to produce meaningful results.
  
  distance = (a, b) ->
    dx = Math.abs(a.x - b.x)
    dy = Math.abs(a.y - b.y)
    Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2))
  
  # The behaviour to be executed with each layout tick.
  tick = (e) ->
    # draw elliptic paths between nodes.
    paths
      .attr("d", (d) ->
        dx = d.target.x - d.source.x
        dy = d.target.y - d.source.y
        dr = Math.sqrt(dx * dx + dy * dy)
        "M" + d.source.x + "," + d.source.y + "A" + dr + "," + dr + " 0 0,1 " + d.target.x + "," + d.target.y)
    # set node positions to positions determined by the layout 
    nodes
      .attr("x", (d) -> d.x - Math.floor(d.width / 2))
      .attr("y", (d) -> d.y - Math.floor(d.height / 2))
      .attr("transform", (d) -> "translate(" + d.x + "," + d.y + ")")
  
  # Stop layout for existing nodes.
  fixAllNodes = () ->
    nodes.each (n) ->
      n.fixed = true
    force.gravity(0)
  
  
  
  # Zoom
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  zoom = () ->
    vis
      #.transition().duration(100)
      .attr("transform", "translate(" + d3.event.translate + ")" + "scale(" + d3.event.scale + ")")
  
  
  
  # Interaction
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~    
  highlightNode = (d, i) ->
    # Utility function to determine if given links and nodes should be highlighted or not.
    linkSelected = (y, n) ->
      (l) -> if l.source == d or l.target == d then y else n
    nodeSelected = (y, n) ->
      (e) -> if e == d or neighboring(d, e) then y else n
  
    # Update element properties based on whether they are highlighted or not.
    labels
      .attr("opacity", linkSelected(1.0, 0.2))
    paths
      .style("stroke", linkSelected(strokeColorHigh, strokeColor))
      .attr("opacity", linkSelected(1.0, 0.2))
    nodeShapes
      .style("stroke", nodeSelected(strokeColorHigh, "none"))
      .style("stroke-width", -> if d3.select(this).attr("class").indexOf("no-stroke") != -1 then 0 else minStrokeWidth)
    nodes
      .attr("opacity", nodeSelected(1.0, 0.2))      
  
  unhighlightNode = (d, i) ->
    # Revert element properties to default if nothing is highlighted.
    labels.attr("opacity", dynamicLinkOpacity)
    paths.style("stroke", strokeColor)
    paths.attr("opacity", dynamicLinkOpacity)
    nodeShapes.style("stroke", "none")
    nodes.attr("opacity", 1.0)
  
  highlightLink = (l, i) ->
    # Utility function to determine if given links and nodes should be highlighted or not.
    linkSelected = (y, n) ->
      (k) -> if k == l then y else n
    nodeSelected = (y, n) ->
      (e) -> if e == l.source or e == l.target then y else n
    
    # Update element properties based on whether they are highlighted or not.
    labels
      .attr("opacity", linkSelected(1.0, 0.2))
    paths
      .style("stroke", linkSelected(strokeColorHigh, strokeColor))
      .attr("opacity", linkSelected(1.0, 0.2))
    nodeShapes
      .style("stroke", nodeSelected(strokeColorHigh, "none"))
      .style("stroke-width", -> if d3.select(this).attr("class").indexOf("no-stroke") != -1 then 0 else minStrokeWidth)
    nodes
      .attr("opacity", nodeSelected(1.0, 0.2)) 
  
  unhighlightLink = (l, i) ->
    # Revert element properties to default if nothing is highlighted.
    labels.attr("opacity", dynamicLinkOpacity)
    paths.style("stroke", strokeColor)
    paths.attr("opacity", dynamicLinkOpacity)
    nodeShapes.style("stroke", "none")
    nodes.attr("opacity", 1.0)
  
  
  # CONSTRUCTOR
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  # Initializes the visualization and returns itself; public methods are placed in its
  # namespace.
  non = () ->
    # Create an SVG that the visualization will be drawn into. Append it into the DOM
    # element 'dom'.    
    svg = d3.select(dom).append("svg")
      .attr("xmlns", "http://www.w3.org/2000/svg")
      .attr("xmlns:xmlns:xlink", "http://www.w3.org/1999/xlink")
      .attr("version", "1.2")
      .attr("width", width)
      .attr("height", height)
      .style("font", normalFont)
      .call(d3.behavior.zoom().scaleExtent([0.5, 3]).on("zoom", zoom))
      .on("dblclick.zoom", leftClickOnly, true)
      .on("mousedown", leftClickOnly, true)
    
    # This rect is needed for correct propagation of mouse events.
    svg.append("rect")
      .attr("width", width)
      .attr("height", height)
      .style("fill", "white")
      .style("pointer-events", "all");
    
    # The container for all elements of the visualization.
    vis = svg.append("g")
    
    # Containers for links and nodes (so that links are always below nodes).
    vis.append("g")
      .attr("id", "links")
    vis.append("g")
      .attr("id", "nodes")
    
    # Preprocess data and add it to the visualization.
    preprocess(data)
    update(data)

  leftClickOnly = ->
    if d3.event.button
      d3.event.stopPropagation()
      d3.event.preventDefault()

  # PUBLIC METHODS
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  non.dom = (value) ->
    if !arguments.length then return dom
    dom = value
    non
  
  # Setter for the graph in JSON format.
  non.graph = (value) ->
    if !arguments.length then return data
    data = value
    non
  
  # Setter for nodes that should be highlighted on next update
  non.highlight = (nodes) ->
    nodesToHighlight = nodes
    non
    
  non.updateAdd = (addition) ->
    addition.links = $.grep addition.links, (l, j) -> $.inArray(l.id, removedLinks) == -1
    $.each addition.nodes, (i, n) ->
      if (!nodesMap.get(n.id))
        data.nodes.push(n)
      else delete addition.nodes[i]
    $.each addition.links, (i, l) ->
      if (!linksMap.get(l.id))
        data.links.push(l)
    preprocess(addition)
    update(data)
  
  non.updateRemove = (nodes) ->
    $.each nodes, (i, node) ->
      removedNodes.push(node.id)
      data.nodes = $.grep data.nodes, (n, j) ->
        n.id != node.id
      data.links = $.grep data.links, (l, j) -> 
        incident = l.source.id == node.id || l.target.id == node.id
        if (incident)
          linksMap.remove(l.id)
          linked["#{l.source.id},#{l.target.id}"] = false
        !incident
      nodesMap.remove(node.id)
    update(data)
  
  non.pauseLayout = () ->
    force.stop()
    layoutPaused = true
    
  non.resumeLayout = () ->
    force
      .nodes(data.nodes)
      .links(data.links)
      .start()
    force.on("tick", tick)
    layoutPaused = false
  
  non.neighbors = (nodeId) ->
    list = ""
    data.nodes.forEach (n) ->
      if (neighboring(nodeId, n))
        list = list + ",#{n.id}"
    list.substring(1)
  
  non.nodeIds = () ->
    list = ""
    data.nodes.forEach (n) ->
      list = list += ",#{n.id}"
    list.substring(1)
  
  non.removedNodeIds = () ->
    removedNodes.join(",")
  
  non.forceParameters = (ld, ls, f, c, t, g) ->
    force
      .linkDistance(CoffeeScript.eval(ld, {bare: true}))
      .linkStrength(eval(ls))
      .friction(eval(f))
      .charge(eval(c))
      .theta(eval(t))
      .gravity(eval(g))
    resumeLayout()
  
  non.addTag = (linkId, tag) ->
    affectedLink = linksMap.get(linkId)
    if ($.inArray(tag, affectedLink.tags) == -1)
      affectedLink.tags.unshift(tag)
    non.setLabel(linkId, tag.label)
  
  non.setLabel = (linkId, label) ->
    vis.select("#label" + linkId)
      .text(label)
  
  non.removeLabel = (linkId, label) ->
    link = linksMap.get(linkId)
    link.tags = $.grep link.tags, (t, j) ->
      t.label != label
    non.setLabel(linkId, if (link.tags.length > 0) then link.tags[0].label else "")
  
  non.removeLink = (linkId) ->
    removedLinks.push(linkId)
    link = linksMap.get(linkId)
    data.links = $.grep data.links, (l, j) -> l.id != link.id
    linksMap.remove(link.id)
    linked["#{link.source.id},#{link.target.id}"] = false
    update(data)


  # Action Handlers (for setting actions that are executed on clicks)
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  non.onNodeClick = (handler) ->
    nodeClickHandler = handler
    non
    
  non.onNodeContextmenu = (handler) ->
    nodeContextmenuHandler = handler
    non
    
  non.onLinkClick = (handler) ->
    linkClickHandler = handler
    non
    
  non.onLinkContextmenu = (handler) ->
    linkContextmenuHandler = handler
    non
    
  non.onLabelClick = (handler) ->
    labelClickHandler = handler
    non
    
  non.onLabelContextmenu = (handler) ->
    labelContextmenuHandler = handler
    non
    

  # RETURN
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  # Return the 'constructor' to the caller for execution and access to public methods.
  non

# Bring this 'class' into public scope.
window.NetworksOfNames = NetworksOfNames
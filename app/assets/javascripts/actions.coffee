#
# Functions that execute actions. Similar to a controller linking the visualization to the UI.
#

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
# SEARCH AND NAVIGATE                                                                           #
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
visElement = '#vis'
currentVis = null
currentExpander = null
currentFrom = undefined
currentTo = undefined

window.showRelationship = (name1, name2, expander, num, from, to) ->
  clearGraph()
  entityUrl = jsRoutes.controllers.Graphs.relationship(name1, name2, expander).url
  fromQ = if(from) then "&from=#{from}" else ""
  toQ = if(to) then "&to=#{to}" else ""
  numQ = if(num) then "&num=#{num}" else ""
  showGlobalLoading()
  $.getJSON entityUrl + numQ + fromQ + toQ, (graph) ->
    focusNodes = $.grep graph.nodes, (n, i) -> n.name == name1 || n.name == name2
    vis = NetworksOfNames()
      .dom(visElement)
      .graph(graph)
      .highlight(focusNodes)
    rememberCurrent(vis, expander, from, to)
    registerMouseHandlers()
    vis()
  .success(-> hideGlobalError())
  .fail(-> showGlobalError("No connection found between #{name1} and #{name2}."))
  .always(-> hideGlobalLoading())

window.showEntity = (name, expander, num, from, to) ->
  clearGraph()
  entityUrl = jsRoutes.controllers.Graphs.entity(name, expander).url
  fromQ = if(from) then "&from=#{from}" else ""
  toQ = if(to) then "&to=#{to}" else ""
  numQ = if(num) then "&num=#{num}" else ""
  showGlobalLoading()
  $.getJSON entityUrl + numQ + fromQ + toQ, (graph) ->
    focusNodes = $.grep graph.nodes, (n, i) -> n.name == name
    vis = NetworksOfNames()
      .dom(visElement)
      .graph(graph)
      .highlight(focusNodes)
    rememberCurrent(vis, expander, from, to)
    registerMouseHandlers()
    vis()
  .success(-> hideGlobalError())
  .fail(-> showGlobalError("#{name1} not found."))
  .always(-> hideGlobalLoading())

registerMouseHandlers = (vis) ->
  if (!vis) then vis = currentVis
  vis
    .onNodeContextmenu(showNodeContextmenu)
    .onLinkClick((l) -> showSources(l.id))
    .onLabelClick((l) -> showSources(l.id))
    .onLinkContextmenu(showLinkContextmenu)
    .onLabelContextmenu(showLinkContextmenu)
  
rememberCurrent = (vis, expander, from, to) ->
  currentVis = vis
  currentExpander = expander
  currentFrom = if (from) then from else undefined
  currentTo = if (to) then to else undefined
 
window.expandMoreNeighbors = (node) ->
  expanded = currentVis.nodeIds()
  ignore = currentVis.removedNodeIds()
  fromQ = if(currentFrom) then "&from=#{currentFrom}" else ""
  toQ = if(currentTo) then "&to=#{currentTo}" else ""
  url = jsRoutes.controllers.Graphs.expandNeighbors(node.id, expanded, ignore, currentExpander).url
  $.getJSON url + fromQ + toQ, (json) ->
    currentVis.updateAdd(json)

window.expandNeighbors = (nodeId, neighbors) ->
  exclude = currentVis.nodeIds()
  fromQ = if(currentFrom) then "&from=#{currentFrom}" else ""
  toQ = if(currentTo) then "&to=#{currentTo}" else ""
  url = jsRoutes.controllers.Graphs.expandNeighborsById(nodeId, exclude, neighbors).url
  $.getJSON url + fromQ + toQ, (json) ->
    currentVis.updateAdd(json)

window.removeLink = (linkId) ->
  currentVis.removeLink(linkId)

window.removeNode = (node) ->
  removeNodes([node])

window.removeNodes = (nodes) ->
  currentVis.updateRemove(nodes)

window.clearGraph = () ->
  $(visElement + ' > svg').remove()

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
# SOURCES                                                                                       #
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
window.showSources = (linkId) ->
  showResultsStats("", "")
  clusteredSourcesUrl = jsRoutes.controllers.Graphs.clusteredSources(linkId, currentFrom, currentTo).url
  emptySourcesModal()
  $('#modal-sources .loading').show()
  $('#modal-sources').modal { backdrop: false }
  $.getJSON clusteredSourcesUrl, (json) ->
    $('#modal-sources .loading').hide()
    drawSources(linkId, json)
  
drawSources = (linkId, json) ->
  if (json.numSources < json.numAllSources)
    $('#sources-list').prepend(
      '<li class="alert alert-info">
        <button type="button" class="close" data-dismiss="alert">&times;</button>
        <strong>Note.</strong> ' + json.numSources + ' of ' + json.numAllSources + ' sources are shown here. You might consider
        limiting the timeframe of your search to reduce the number of sources.
      </li>'
    )
  $.each json.clusters, (i, cluster) ->
    # append the source(s) to the document
    $('#sources-list').append(
      '<li class="source">' +
        clusterWidget(cluster.proxies, cluster.rest, json.entity1, json.entity2, linkId, json.tags) +
      '</li>'
    )
  showResultsStats(json.numSources, json.numClusters)

makeJsonCall = (url) ->
  $.ajax {
    dataType: "json",
    url: url
  }

showResultsStats = (numSources, numClusters) ->
  stats = "#{numSources} sources in #{numClusters} clusters"
  $('#sources-results-stats').html(stats)

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
# NEIGHBORS                                                                                     #
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
window.showNeighbors = (node) ->
  exclude = currentVis.nodeIds()
  url = jsRoutes.controllers.Graphs.neighbors(node.id, exclude, currentExpander).url
  fromQ = if(currentFrom) then "&from=#{currentFrom}" else ""
  toQ = if(currentTo) then "&to=#{currentTo}" else ""
  emptyNeighborsModal()
  $('#modal-neighbors .loading').show()
  $('#modal-neighbors').modal { backdrop: false }
  $.getJSON url + fromQ + toQ, (json) ->  
    $('#modal-neighbors .loading').hide()
    $('#modal-neighbors #neighbors-of-name').html(node.name)
    $('#modal-neighbors #neighbors-of-id').val(node.id)
    $.each json.neighbors, (i, neighbor) ->
      $('#modal-neighbors #neighbors-list').append(
        '<li class="neighbor">
          <label class="checkbox">
            <input type="checkbox" value="' + neighbor.id + '">
            ' + neighbor.name + '
          </label>
        </li>'
      )

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
# TAGGING                                                                                       #
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
window.setLabel = (linkId, label) ->
  currentVis.setLabel(linkId, label)
  if (label)
    confirmLabel(linkId, label)

window.confirmLabel = (linkId, label) ->
  $.get jsRoutes.controllers.Tags.castPositiveVoteByLabel(linkId, label).url
  checkTagsWithLabel(linkId, label)

window.removeLabel = (linkId, label) ->
  currentVis.removeLabel(linkId, label)
  rejectLabel(linkId, label)

window.rejectLabel = (linkId, label) ->
  $.get jsRoutes.controllers.Tags.castNegativeVoteByLabel(linkId, label).url
  removeTagsWithLabel(linkId, label)

window.addTag = (relationship, sentence, label, direction, callback) ->
  url = jsRoutes.controllers.Tags.add(relationship, sentence, label, direction).url
  $.getJSON url, (tag) ->
    if tag
      callback(tag)
      if (currentVis)
        currentVis.addTag(tag.relationship, tag)
        confirmLabel(tag.relationship, tag.label)
        
        

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
# LAYOUT                                                                                        #
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
window.pauseLayout = () ->
  if currentVis
    currentVis.pauseLayout()

window.resumeLayout = () ->
  if currentVis
    currentVis.resumeLayout()

window.applyForceSettings = () ->
  currentVis.forceParameters(
    $("#fs-linkDistance").val(),
    $("#fs-linkStrength").val(),
    $("#fs-friction").val(),
    $("#fs-charge").val(),
    $("#fs-theta").val(),
    $("#fs-gravity").val()
  )

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
# EXPORT                                                                                        #
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
window.exportToSVG = () ->
  if currentVis
    showGlobalLoading()
    serializer = new XMLSerializer()
    svg = serializer.serializeToString($('svg')[0])
    #open("data:image/svg+xml," + encodeURIComponent(svg))
    blob = new Blob([svg], { type: "image/svg+xml;charset=utf-8" })
    hideGlobalLoading()
    saveAs(blob, "non-export.svg")

window.exportToPNG = () ->
  if currentVis
    showGlobalLoading()
    serializer = new XMLSerializer()
    svg = serializer.serializeToString($('svg')[0])
    url = jsRoutes.controllers.Application.svgToPNG().url
    $.ajax(
      url: url,
      processData: false,
      type: "POST"
      data: svg,
      contentType: "text/plain;charset=UTF-8",
      dataType: "json",
      success: (result) ->
        getFile(result.uuid)
    )
    .success(-> hideGlobalError())
    .fail(-> showGlobalError("Error converting the visualization to PNG."))
    .always(-> hideGlobalLoading())

getFile = (uuid) ->
  url = jsRoutes.controllers.Application.serveFile(uuid).url
  $('#downloader-iframe').attr("src", url)
#
# Configuration and functions related to the user interface.
#

jQuery ->
  #$(".tooltipped").tooltip({
  #  placement: "right"
  #})

  # Menu Actions
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  # New
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~
    
  $("#new-sample-spenden1999").click ->
    showRelationship "Walther Leisler Kiep", "Karlheinz Schreiber", "npmi", 10, "1999-01", "2004-12"
  
  $("#new-sample-rwe2004").click ->
    showRelationship "Hermann-Josef Arentz", "RWE", "npmi", 10, "2004-10", "2004-12"
  
  $("#new-sample-liechtenstein2004").click ->
    showRelationship "Liechtensteiner LGT-Bank", "BND", "npmi", 10, "2006-01", undefined
    
  $("#new-sample-kundus2009").click ->
    showRelationship "Georg Klein", "ISAF", "npmi", 5, "2009-04", undefined
    
  $("#new-sample-harrypotter").click ->
    showEntity "Harry Potter", "npmi", 12
    
  $("#new-sample-jamesbond").click ->
    showEntity "James Bond", "npmi", 12
    
  $("#new-sample-charlesdarwin").click ->
    showEntity "Charles Darwin", "npmi", 12
  
  $("#new-clear").click ->
    clearGraph()
  
  # Export
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~
  $("#export-svg").click ->
    exportToSVG()
    
  $("#export-png").click ->
    exportToPNG()
  
  # Layout Play/Pause
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~
  
  $("#force-play").click ->
    resumeLayout()
    $(this).addClass("active") # prevent bootstrap buttons from getting stuck for some reason
  
  $("#force-pause").click ->
    pauseLayout()
    $(this).addClass("active") # prevent bootstrap buttons from getting stuck for some reason
  
  # UI Samples
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~
  
  $("#uisample-sources").click ->
    showSources(100824)
  


  # CONTEXT MENU
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  
  contextmenu = $("#contextmenu")
  contextmenuList = $("#contextmenu ul")
  contextmenu.hide()
  divider = {}
  
  window.showNodeContextmenu = (node, x, y) ->
    renderContextMenu([
      { name: "Expand more", action: -> expandMoreNeighbors(node) },
      { name: "Expand...", action: -> showNeighbors(node) },
      { name: "Remove node", action: -> removeNode(node) },
    ], x, y)
  
  window.showLinkContextmenu = (link, x, y) ->
    labels = $.map link.tags, (tag) -> tag.label
    unique = labels.filter (elem, pos) ->
      labels.indexOf(elem) is pos
    labelItems = $.map unique, (label) -> {
      name: '<button class="close remove-label" style="position: relative;" data-link="' + link.id + '" data-label="' + label + '">&times;</button>' + label,
      action: -> setLabel(link.id, label) }
    renderContextMenu([
      { name: "Select label", action: labelItems },
      { name: "Hide label", action: -> setLabel(link.id, "") },
      divider,
      { name: "Remove edge", action: -> removeLink(link.id) }
    ], x, y)
  
  window.showTagContextmenu = (tagId, linkId, label, x, y) ->
    renderContextMenu([
      { name: "Set this label", action: -> setLabel(linkId, label) },
      { name: "Confirm label", action: ->
        confirmLabel(linkId, label)
      },
      { name: "Remove label", action: -> removeLabel(linkId, label) }
    ], x, y, true)
  
  hideContextmenu = () ->
    contextmenu.hide()
    contextmenu.removeClass("above-modal")
    contextmenuList.empty()
  
  $(document).on "click", "html", ->
    hideContextmenu()
  
  renderContextMenu = (items, x, y, aboveModal) ->
    hideContextmenu()
    $.each items, (i, item) ->
      if item is divider
        $('<li class="divider"></li>').appendTo(contextmenuList)
      else
        if $.isArray(item.action)
          submenu = $('<li class="dropdown-submenu"><a tabindex="-1" href="#">' + item.name + '</a>
                        <ul id="context-submenu' + i + '" class="dropdown-menu">
                        </ul>
                       </li>')
          submenu.appendTo(contextmenuList)
      
        parentList = if (submenu) then submenu.find(".dropdown-menu") else contextmenuList
        subitems = if $.isArray(item.action) then item.action else [item]
        $.each subitems, (j, subitem) ->
          element = $('<li><a tabindex="-1" href="#">' + subitem.name + '</a></li>')
          element.appendTo(parentList)
          element.on "click", "a", (e) ->
            if e.target == this # prevent clicks on containing elements from triggering the action
              subitem.action()
    
    contextmenu.css {
      position: "absolute",
      left: x,
      top: y
    }
    if (aboveModal) then contextmenu.addClass("above-modal")
    contextmenu.show()
    
  $('#contextmenu').on "click", ".remove-label", (e) ->
    link = $(this).attr("data-link")
    label = $(this).attr("data-label")
    removeLabel(link, label)
  
  
  # TYPEAHEAD AND INPUT VALIDATION
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  
  # Ask the server for suggestions as the user types.
  $(".ajax-typeahead").typeahead
    minLength: 2
    items: 5
    source: (query, process) ->
      input = $(this)[0].$element.val()
      $.ajax
        url: jsRoutes.controllers.Application.typeahead(input).url
        type: "GET"
        dataType: "json"
        success: (json) ->
          (if typeof json.options is "undefined" then false else process(json.options))
  
  # Validate user input and change input class to error/success accordingly.
  $("#new-r-person1, #new-r-person2, #new-n-person").change ->
    cgroup = $(this).parents("div.control-group")
    input = $.trim($(this).val())
    if input is ""
      cgroup.removeClass "success"
      cgroup.removeClass "error"
    else
      $.ajax
        dataType: "json"
        url: jsRoutes.controllers.Application.nameExists(input).url
        type: "GET"
        success: (data) ->
          if data.exists
            cgroup.removeClass "error"
            cgroup.addClass "success"
          else
            cgroup.removeClass "success"
            cgroup.addClass "error"

  
  # MODAL BEHAVIOUR
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  
  # Reset form when the user closes a modal.
  $("modal-new").on "hidden", ->
    $(this).find("form")[0].reset()
  
  # Query the server for network when the user submits a new search.  
  $("#new-go").click ->
    searchType = if $('#tabs li.active > a').attr("href") is "#new-by-name" then "n" else "r"
    if searchType is "r"
      showRelationship $("#new-r-person1").val(), $("#new-r-person2").val(), $("#new-r-expander").val(), $("#new-r-num-nodes").val(), $("#new-r-from").val(), $("#new-r-to").val()
    else
      showEntity $("#new-n-person").val(), $("#new-n-expander").val(), $("#new-n-num-nodes").val(), $("#new-n-from").val(), $("#new-n-to").val()
  
  $("#new-r-clearfrom").click -> $("#new-r-from").val("")
  $("#new-r-clearto").click -> $("#new-r-to").val("")
  $("#new-n-clearfrom").click -> $("#new-n-from").val("")
  $("#new-n-clearto").click -> $("#new-n-to").val("")    
  
  # Clear sources if the user closes the sources modal.
  $("#modal-sources").on "hidden", (e) ->
    if $(e.target).hasClass("modal") # accordion also fires "hidden" events; only empty if the target was the modal
      emptySourcesModal()
  
  window.emptySourcesModal = () ->
    $("#sources-list").empty()

  $("#modal-neighbors").on "hidden", (e) ->
    if $(e.target).hasClass("modal") # accordion also fires "hidden" events; only empty if the target was the modal
      emptyNeighborsModal()
  
  window.emptyNeighborsModal = () ->
    $("#neighbors-of-name").html("")
    $("#neighbors-of-id").val()
    $("#neighbors-filter").val("")
    $("#neighbors-list").empty()
  
  $("#neighbors-go").click ->
    selected = $("#neighbors-form input:checked")
      .map(-> this.value)
      .get()
    if (selected.length > 0)
      expandNeighbors($("#neighbors-of-id").val(), selected)
  
  $("#neighbors-filter").on "propertychange keyup input paste", ->
    filter = $("#neighbors-filter").val()
    if (filter)
      $("#neighbors-list li:icontains('" + filter + "')").show()
      $("#neighbors-list li:not(:icontains('" + filter + "'))").hide()
    else
      $("#neighbors-list li").show()
    
  $("#neighbors-filter-reset").click ->
    $("#neighbors-filter").val("")
    
  # TAGS
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  $("#modal-sources").on "keypress", ".add-tag-input", (e) ->
    if e.which == 13
      # get label and direction and add it before the input box (i.e. into the list of tags)
      e.preventDefault()
      label = $(this).val()
      direction = $(this).closest("li").find("#" + $(this).attr("id") + "-direction").val()
      $(this).val("")
      
      # split the input name to get relationship and sentence ids
      input = $(this)
      ref = input.attr("name").substr(4).split("-")
      addTag(ref[0], ref[1], label, direction, (tag) ->
        input.closest("li").before(renderTag(tag)))
  
  $("#modal-sources").on "click", ".tag-direction-toggle", ->
    widget = $(this).closest("li") 
    id = widget.find("input.add-tag-input").attr("id")
    direction = widget.find("#" + id + "-direction")
    if direction.val() == "r"
      direction.val("l")
      widget.find(".tag-direction-left").css("visibility", "visible")
      widget.find(".tag-direction-right").css("visibility", "hidden")
    else if direction.val() == "l"
      direction.val("b")
      widget.find(".tag-direction-left").css("visibility", "visible")
      widget.find(".tag-direction-right").css("visibility", "visible")
    else
      direction.val("r")
      widget.find(".tag-direction-left").css("visibility", "hidden")
      widget.find(".tag-direction-right").css("visibility", "visible")
      
  $("#modal-sources").on "contextmenu", ".tag", (e) ->
    e.preventDefault()
    tagId = $(this).attr("data-tag")
    linkId = $(this).attr("data-link")
    label = $(this).find(".label-text").html()
    showTagContextmenu(tagId, linkId, label, e.clientX, e.clientY)

  window.renderTag = (tag) ->    
    '<li><span class="tag label' + (if (!tag.auto) then " label-info" else "") + '" data-tag="' + tag.id + '" data-link="' + tag.relationship + '">' +
    (if tag.direction == "l" or tag.direction == "b" then "&#9668; " else "") +
    '<span class="label-text">' + tag.label + '</span>' +
    '<span style="display: ' + (if (tag.hasPositive) then "inline" else "none") + '" class="label-confirmed">  &#10003;</span>' +
    (if tag.direction == "r" or tag.direction == "b" then " &#9658;" else "") +
    '</span></li>'
  
  window.checkTagsWithLabel = (linkId, label) ->
    $("#modal-sources").find(".tag[data-link='#{linkId}']").closest("li").each ->
      if $(this).find(".label-text").html() == label
        $(this).find(".label-confirmed").css("display", "inline")
  
  window.removeTagsWithLabel = (linkId, label) ->
    $("#modal-sources").find(".tag[data-link='#{linkId}']").closest("li").each ->
      if $(this).find(".label-text").html() == label
        $(this).remove()
        
  # ERRORS AND LOADING
  # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  window.showGlobalLoading = () ->
    $("#global-loading").css("visibility", "visible")
  
  window.hideGlobalLoading = () ->
    $("#global-loading").css("visibility", "hidden")
    
  window.showGlobalError = (message) ->
    $("#notifications").append(
      '<div class="alert alert-error">
        <button type="button" class="close" data-dismiss="alert">&times;</button>
        <strong>Error!</strong> ' + message + '
      </div>'
    )
  
  window.hideGlobalError = () ->
    $("#notifications").empty()

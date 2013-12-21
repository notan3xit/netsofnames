#
# UI functions concerned with drawing elements in the sources view.
#

# The cluster widget consisting of a surrounding box, up to three representant (proxy) elements and the remaining elements hidden behind
# a expandable link. 
window.clusterWidget = (proxies, rest, e1, e2, relId, tagsBySentence) ->
  widget = ""
  $.each proxies, (i, proxy) ->
    widget += sourceWidget(proxy, e1, e2, relId, tagsBySentence[proxy.sentence.id])
  widget += if (rest.length > 0)
    hiddenSources(rest, e1, e2, relId, tagsBySentence)
  else ''
  '<div class="sources-cluster">' +
    widget +
  '</div>'



# The widget for a single source, consisting of the quote, its source and a tagging widget.
window.sourceWidget = (source, e1, e2, relId, tags, hidden) ->
  '<blockquote>' +
    source.sentence.text
      .replace(e1.name, "<strong>" + e1.name + "</strong>")
      .replace(e2.name, "<strong>" + e2.name + "</strong>") +
    '<small class="cite"><a href="' + source.source + '" target="_blank">' + source.source + '</a> on ' + source.date + '</small>' +
    tagWidget(source, e1, e2, relId, tags, hidden) +
  '</blockquote>'



# The list of sources that are initially hidden (i.e. not the cluster representants.)
window.hiddenSources = (rest, e1, e2, relId, tagsBySentence) ->
  hiddenId = 'hidden' + relId + '-' + rest[0].sentence.id
  sources = ""
  $.each rest, (i, source) ->
    sources += sourceWidget(source, e1, e2, relId, tagsBySentence[source.sentence.id], true)
  '<div id="' + hiddenId + '" class="accordion">
    <div class="accordion-group">
      <div class="accordion-heading">
        <a class="accordion-toggle" data-toggle="collapse" data-parent="#' + hiddenId + '" style="text-align:center;" href="#' + hiddenId + '-content">
          and ' + rest.length + ' similar sentence' + (if (rest.length > 1) then "s" else "") + '
        </a>
      </div>
      <div id="' + hiddenId + '-content" class="accordion-body collapse">
        <div class="accordion-inner">' +
          sources +
        '</div>
      </div>      
    </div>
  </div>'



# The tagging widget, consisting of a list of existing tags, entity names, an input field for new tags, direction display and toggle.
window.tagWidget = (source, e1, e2, relId, tags, hidden) ->
  tagsId = 'tags' + relId + '-' + source.sentence.id
  existingTags = ""
  if (tags) then $.each tags, (i, tag) ->
    existingTags += renderTag(tag)
  '<div class="tagging-widget">
    <ul id="' + tagsId + '" name="' + tagsId + '-tags" class="tag-box">' +
      existingTags +
      '<li class="tag-input">
        <table><tr>
          <td class="tag-cell-left">' + e1.name + '</td>
          <td class="tag-cell-center">
            <span class="tag-direction tag-direction-left" style="visibility: hidden;">&#9668;</span>
            <input type="hidden" id="' + tagsId + '-direction" value="r" />
            <input type="text" id="' + tagsId + '" name="' + tagsId + '" placeholder="add tag" class="input-small add-tag-input" />
            <span class="tag-direction tag-direction-right">&#9658;</span><br />
            <button class="btn btn-mini tag-direction-toggle">toggle direction</button>
          </td>
          <td class="tag-cell-right">' + e2.name + '</td>
        </tr></table>
      </li>
    </ul>
  </div>'
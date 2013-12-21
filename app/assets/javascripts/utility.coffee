#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
# adds dimensions to String prototype;                                                          #
# returns an array where the first element is the width and the second the height of a string,  #
# given a font (or the body element's font)                                                     #
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
String::dimensions = (font) ->
  f = font || $('body').css("font-size") + " " + $('body').css("font-family")
  o = $('#stringbox')
  o.text(this).css({'font': f})
  return [o.width() + 1, o.height() + 1]

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
# adds hashCode to String prototype                                                             #
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
String::hashCode = ->
  hash = 0
  return hash if @length is 0
  i = 0
  while i < @length
    c = @charCodeAt(i)
    hash = ((hash << 5) - hash) + c
    hash |= 0
    i++
  hash

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
# adds case-insensitive :contains selector to jQuery                                            #
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
jQuery.expr[':'].icontains = (a, i, m) ->
  jQuery(a).text().toLowerCase().indexOf(m[3].toLowerCase()) >= 0;

#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
# adds a distinct method to Array prototype;                                                    #
# returns the array where each element is contained at most once                                #
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~#
Array::distinct = -> 
  @filter (elem, pos) ->
    @indexOf(elem) is pos
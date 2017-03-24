
<div class="box">

    <h2>
        <g:isUserAdmin>

            <div class="action-menu" style="position:absolute;top:5px;right:5px">
                <button class="action-btn">
                    <img src="${resource(dir: 'images/icons/silk', file: 'cog.png')}" style="vertical-align: middle"/>
                </button>
                <div class="actions">
                    <div class="action-menu-item">

                        <g:if test="${!params.editTags}">
                            <g:link controller="dashboard" action="index" params="[editTags:true]">
                                <img src="${resource(dir:'images/icons/silk',file:'pencil.png')}" style="vertical-align: middle" />
                                <warehouse:message code="tag.editTags.label" default="Edit tags"></warehouse:message>
                            </g:link>
                        </g:if>
                        <g:else>
                            <g:link controller="dashboard" action="index">
                                <img src="${resource(dir:'images/icons/silk',file:'control_end.png')}" style="vertical-align: middle" />
                                <warehouse:message code="tag.doneEditing.label" default="Done editing"></warehouse:message>
                            </g:link>
                        </g:else>
                    </div>
                </div>
            </div>
        </g:isUserAdmin>


        <warehouse:message code="tags.label" default="Tags"/>
    </h2>

	<div class="widget-content">
        <div id="tag-summary">
            <g:if test="${params.editTags}">
                <g:isUserAdmin>
                    <table>
                        <thead>
                        <tr>
                            <th><warehouse:message code="tag.name.label" default="Tag"/></th>
                            <th><warehouse:message code="tag.count.label" default="Count"/></th>
                            <th><warehouse:message code="tag.active.label" default="Active"/></th>
                            <th><warehouse:message code="default.actions.label"/></th>
                        </tr>
                        </thead>
                        <tbody>
                        <g:each in="${tags }" var="tag" status="i">
                            <tr class="${i%2?'odd':'even'}">
                                <td>
                                    ${tag.key.tag?:"Empty tag"}
                                </td>
                                <td>
                                    ${tag?.value}
                                </td>
                                <td>
                                    ${tag.key.isActive}
                                </td>
                                <td>
                                    <g:link controller="dashboard" action="hideTag" id="${tag?.key?.id}" params="[editTags:true]">
                                        <img src="${resource(dir:'images/icons/silk',file:'bullet_cross.png')}"/></g:link>
                                </td>
                            </tr>
                        </g:each>
                        </tbody>
                    </table>
                </g:isUserAdmin>
                <g:if test="${!tags}">
                    <div class="center fade empty">
                        <warehouse:message code="tag.noTags.label"/>
                    </div>
                </g:if>
            </g:if>
            <g:else>
                <g:if test="${tags}">
                    <div id="tagcloud">
                        <g:each in="${tags }" var="tag">
                            <g:if test="${tag.value > 1}">
                                <g:link controller="inventory" action="browse" params="['tag':tag.key]" rel="${tag.value }">
                                    ${tag.key.tag?:"Empty tag" } (${tag?.value })</g:link>
                            </g:if>
                        </g:each>
                    </div>
                </g:if>
                <g:else>
                    <div style="margin:10px;" class="center fade empty">
                        <warehouse:message code="tag.noTags.label"/>
                    </div>
                </g:else>
            </g:else>
        </div>

		<div class="clear"></div>
	</div>
</div>
<script src="${resource(dir:'js/jquery.tagcloud', file:'jquery.tagcloud.js')}" type="text/javascript" ></script>

<script>

    $(window).load(function(){
        $("#tagcloud a").tagcloud({
            size: {
                start:1.2,
                end: 1.5,
                unit: 'em'
            },
            color: {
                start: "#aaa", // "#CDE"
                end: "#F52"//"#FS2"
            }
        });
    });

</script>
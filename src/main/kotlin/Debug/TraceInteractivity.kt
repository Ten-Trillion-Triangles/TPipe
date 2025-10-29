package com.TTT.Debug

object TraceInteractivity 
{
    fun generateJavaScript(nodes: List<TraceNode>): String 
    {
        return """
            <script>
                window.scrollToEvent = function() {
                    const nodeId = arguments[0] || 'unknown';
                    const pipeName = getNodePipeName(nodeId);
                    const firstEventRow = document.querySelector(`.event-card[data-pipe="${'$'}{pipeName}"]`) 
                        || document.querySelector(`.trace-item[data-pipe="${'$'}{pipeName}"]`);
                    
                    if (firstEventRow) {
                        highlightPipeEvents(pipeName);
                        firstEventRow.scrollIntoView({ 
                            behavior: 'smooth', 
                            block: 'center' 
                        });
                        flashHighlight(firstEventRow);
                    }
                }
                
                window.getNodePipeName = function(nodeId) {
                    const nodeMap = ${generateNodeMap(nodes)};
                    return nodeMap[nodeId] || '';
                }
                
                window.highlightPipeEvents = function(pipeName) {
                    document.querySelectorAll('.trace-item').forEach(element => {
                        element.classList.remove('highlighted');
                    });
                    
                    document.querySelectorAll(`.trace-item[data-pipe="${'$'}{pipeName}"]`).forEach(element => {
                        element.classList.add('highlighted');
                    });
                }
                
                window.flashHighlight = function(element) {
                    element.classList.add('flash-highlight');
                    setTimeout(() => {
                        element.classList.remove('flash-highlight');
                    }, 2000);
                }
                
                mermaid.initialize({ 
                    startOnLoad: true,
                    theme: 'default',
                    flowchart: { 
                        useMaxWidth: true, 
                        htmlLabels: true 
                    },
                    securityLevel: 'loose'
                });
            </script>
        """.trimIndent()
    }
    
    private fun generateNodeMap(nodes: List<TraceNode>): String 
    {
        val map = nodes.associate { it.nodeId to it.pipeName }
        return map.entries.joinToString(",", "{", "}") { 
            "\"${it.key}\": \"${it.value}\"" 
        }
    }
}

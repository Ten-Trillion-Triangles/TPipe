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
                    const firstEventRow = document.querySelector(`tr[data-pipe="${'$'}{pipeName}"]`);
                    
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
                    document.querySelectorAll('.trace-row').forEach(row => {
                        row.classList.remove('highlighted');
                    });
                    
                    document.querySelectorAll(`tr[data-pipe="${'$'}{pipeName}"]`).forEach(row => {
                        row.classList.add('highlighted');
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

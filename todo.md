**Bug fixes**
- ~~Fix tracing verbosity issue in the manifold class.~~
- Ensure sub pipes token counts track upwards to the parent pipe. And the parent pipe, tracks upwards yet again to
the pipeline itself.
- Fix reasoningBudget subtraction in `Pipe.setTokenBudgetInternal` so it adjusts the prompt segment (system or user) where reasoning is injected rather than always shrinking `maxTokens`; preserve `ReasoningInjector`/metadata awareness so multi-round reasoning consumes the correct bucket.
- Fix multi round reasoning issues.
- Fix janky internal model reasoning where some providers work and others do not.
- Consider renaming the "problemStatement" var in explicit cot reasoning to avoid it making assumptions in cases
where assumptions are not desired.

**Enhancements:**
- ~~Add creator functions to help simplify creating converseHistory adds.~~
- Add human in the loop functions to the advanced container classes.
- ~~Add link table to trace file system.~~
- ~~Better flesh out the manifold builder.~~
- ~~Add support for binding callbacks when each pipe in a pipeline clears.~~
- ~~Add aws tier support to bedrock pipe to allow for other tiers that now exist on bedrock.~~
- ~~Add support for keyword matching similar to the lorebook for context elements, and converse history stored in
a context window object. This would allow them to be hit on selection at higher truncation priority than the rest
allowing for expanded semantic reach.~~
- Add support for requirement function in the lorebook in order to allow for custom requirements needing to be met 
in order to accept a key being hit.
- Add support locking and unlocking lorebook keys, leverage the requirement function system as an addition.
- Add output for just after generation, and prior to transformation function. Name it something easy to search in the 
trace file, since transformation functions can change the output in a way that's invisible to the developer. This leads
to confusion when reading a trace file if a transformation function is overwriting the original output to something
entirely different from what the pipe produced.
- ~~Consider having jump instructions override terminate instructions.~~

**New features:**
- ~~Add DistributionGrid class that enables "swarm" style agents with TPipe pipelines.~~
- ~~Expand tracing system to help trace Splitters, Manifolds, Junctions, and DistributionGrid objects.~~
- ~~Add support for pulling pipeline context, then merging into global.~~
- Create builders for all the containers, and general workflows like chain of thought pipelines
- Add compression as a function
- Add compression and decompression prompt injection support using semantic compression of human languages to strip out
all the components that don't convey information while retaining all the ones that do.
- ~~Add support for advanced token budget controls which will go beyond just max out vs context in.~~
- ~~Add support for mini bank context in pipelines.~~
- ~~Add support for automatic mini bank updates from pipes to pipeline, or global.~~
- ~~Add support for persisting lorebook keys that allow a pointer to a file, then load that file when the key is referenced.
Allow the keys to also be stored as stub files and loaded directly.~~
- ~~Add default .tpipe dir support for core config and persistent storage features built into the library.~~
- ~~Add support for a todo list style mechanism for storing and tracking progress.~~
- ~~Add automatic wrapping of pipeline inputs and outputs into [ConverseHistory] structure.~~
- Add support for pipeline reflection allowing agents, containers, and P2PInterface objects to reflect on their
state, settings, internal pipelines etc. and update and adjust them and their own internal context in real time.
- ~~Add delegate to report when a pipe has finished it's work in a pipeline.~~ 
- Add native pcp support functions to allow various runtime support. Examples include querying TPipe memory data at
runtime, Interacting with TPipe systems and settings, inspecting and registering pcp and p2p systems. This is a great
compliment to supporting model reflection in TPipe.
- Add async container class that produces a result pointer and immediate response that can be then checked on, 
and automatically propagated to interrupt a manifold class and interject its result.

**Down the road:**
- Create entry and exit points for graalvm native for future non jvm language wrappers.
- Create language wrappers for: js, C#, C++ (If possible, maybe with reflection if the modern standards ever catch up?),
lua, GDscript, Rust, Swift, Unreal Engine and Golang.
- add remote trace support
- add remote context support
- add pcp server support 
- add p2p server support
- ~~Add support for AWS agent core.~~ //Doesn't provide any useful features apparently.
- Add support for kotlin script in PCP.
- Add support for aws bedrock guardrails.

**New builders:**
- ~~Reasoning pipe builder that turns non-reasoning pipes into reasoning pipes by applying some assisting built in
functions.~~
- Lorebook pipe builder that builds a generic pipe that can record to a lorebook that can be hooked into from the
ContextBank automatically.
- Compression pipe builder that can build a pipe or pipeline that will handle semantic compression.
- Add injector for semantic decompression.
- various manager/judge pipelines for the advanced container classes
- Add pipeline plus built-in pcp functions to support breaking down a large level of context into a relationship key
  map using the lorebook system. Which allows an llm expand context in steps rather than pulling it in all at once and
  enabling more targeted lorebook strategies where memory needs to be retrieved after the user prompt. This is technically
  possible to do now but adding convince systems similar to what TPipe-Defaults does is a good idea.
- Add todolist pipeline that can build and save todo lists.
- Create builders for compression pipelines.

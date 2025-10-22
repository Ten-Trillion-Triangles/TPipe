**Bug fixes**

**Enhancements:**
~~- Add creator functions to help simplify creating converseHistory adds.~~
- Add human in the loop functions to the advanced container classes.
- Add link table to trace file system.

**New features:**
- Add DistributionGrid class that enables "swarm" style agents with TPipe pipelines.
- Expand tracing system to help trace Splitters, Manifolds, Junctions, and DistributionGrid objects.
- Add support for pulling pipeline context, then merging into global.
- Create builders for all the containers, and general workflows like chain of thought pipelines
- Create builders for compression pipelines
- Add compression as a function
- Add compression and decompression prompt injection support using semantic compression of human languages to strip out
all the components that don't convey information while retaining all the ones that do.
~~- Add support for advanced token budget controls which will go beyond just max out vs context in.~~
~~- Add support for mini bank context in pipelines.~~
~~- Add support for automatic mini bank updates from pipes to pipeline, or global.~~

**Down the road:**
- Create entry and exit points for graalvm native for future non jvm language wrappers.
- Create language wrappers for: js, C#, C++ (If possible, maybe with reflection if the modern standards ever catch up?),
lua, GDscript, Rust, Swift, Unreal Engine and Golang.
- add remote trace support
- add remote context support
- add pcp server support 
- add p2p server support
- Add support for AWS agent core.
- Add support for kotlin script in PCP.

**New builders:**
~~- Reasoning pipe builder that turns non-reasoning pipes into reasoning pipes by applying some assisting built in
functions.~~
- Lorebook pipe builder that builds a generic pipe that can record to a lorebook that can be hooked into from the
ContextBank automatically.
- Compression pipe builder that can build a pipe or pipeline that will handle semantic compression.
- Add injector for semantic decompression.
- various manager/judge pipelines for the advanced container classes

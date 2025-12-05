- If and else statements must follow the following formatting patterns if they are standalone:
if (truncateContextAsString)
{
contextWindow.combineAndTruncateAsString(
"",
contextWindowSize,
multiplyWindowSizeBy,
contextWindowTruncation,
countSubWordsInFirstWord,
favorWholeWords,
countOnlyFirstWordFound,
splitForNonWordChar,
alwaysSplitIfWholeWordExists,
countSubWordsIfSplit,
nonWordSplitCount
)
}

         else 
         {
             contextWindow.selectAndTruncateContext(
                 "",
                 contextWindowSize,
                 multiplyWindowSizeBy,
                 contextWindowTruncation,
                 countSubWordsInFirstWord,
                 favorWholeWords,
                 countOnlyFirstWordFound,
                 splitForNonWordChar,
                 alwaysSplitIfWholeWordExists,
                 countSubWordsIfSplit,
                 nonWordSplitCount
             )
         }

- Functions must follow the following formatting:
  fun setValidatorFunction(func: (json: String) -> Boolean): Pipe
  {
  this.validatorFunction = func
  return this
  }
- When statements should be formatted like this:
```
  when(command) {
  "help" -> printHelp() //Prints a list of commands.
  "set-engine" -> setEngine()  //Set the engine configuration. Required to be done in setup for all other commands
  "set-project" -> setProject() //Set a project configuration.
  "exit" -> exitProcess(0) //Exit the program.
  "build" -> buildProject() //Build an unreal engine project. Equal to building from your IDE.
  "package" -> packageProject() //Package an unreal engine project.
  "module" -> buildModule() //Build an unreal engine module.
  "plugin" -> buildPlugin() //Build an unreal engine plugin.
  "generate" -> generateProjectFiles() //Generate project files.
  "switch" -> switchVersion() //Switch project to a different engine version.
  "set-launch" -> setLaunch() //Set a launch configuration for an external program.
  "run" -> runLaunch() //Run a saved launch config.
  "donate" -> setDonorPath() //Sets a donor path to collect engine version data from. Required for macOS.
  "swap" -> swap() //Switch to another engine configuration
  "default" -> default() //Set the default engine configuration
  "make" -> runMake() //Run the make command for unreal engine or equivalent for non Linux platforms.
  "register" -> register() //Register the engine with UAT.
  "flag-alias" -> flagAlias() //Add or update a flag alias
  "merge-flags" -> mergeFlagAliases() //Merge two flag aliases into a single one
  "list-flags" -> listFlags() //List all flag aliases
  "import" -> import() //Import project config as a template for another project
  "install" -> install() //Developer command to package up ubuild binaries in a deployment ready state.
  "path" -> path() //Update project path to a new path.
  "def-flags" -> generateDefaultFlags() //Used to un-fuck developer config files.
  "clear" -> Clear() //todo: Fix this so it actually works.
  "info" -> printResources() //Prints useful links and resources regarding ubt and uat.
  "set-uat" -> setUat() //Set a launch alias using the path to UAT and any arguments you want to pass to it.
  "zip-project" -> zipProject() //todo: Fix bug in UAT that prevents zipping up projects.
  "config"  -> config(false) //Print engine configuration information.
  "config-verbose" -> config(true) //Print engine configuration information with more detail.
  "remove-project" -> removeProject() //Delete a project configuration.
  "remove-engine" -> removeEngine() //Delete an engine configuration.
  "list-run" -> listRun() //Print all saved launch string configurations.
  else -> error = true
  }
  ```

- These rules should be followed provided the code will compile. Some functions keywords, and other kotlin features
require the { to be to the right of the symbol and in those cases it must be maintained to ensure the code compiles

When writing functions you must:

- Place the opening { below the parameters instead of to the right of them. As an example:
  /**
    * Read DAP headers
      */
      private fun readHeaders(): Map<String, String>
      {
      val headers = mutableMapOf<String, String>()
      var line = readLine()

      while (line.isNotEmpty())
      {
      val colonIndex = line.indexOf(':')
      if (colonIndex > 0) {
      val key = line.substring(0, colonIndex).trim().lowercase()
      val value = line.substring(colonIndex + 1).trim()
      headers[key] = value
      }
      line = readLine()
      }
      return headers
      }

- Place { braces below for and while loops. As an example:
  //Populate the mini bank for multi page key setup.
  for(page in pageKeyList)
  {
  val pagedContext = ContextBank.getContextFromBank(page)
  miniContextBank.contextMap[page] = pagedContext
  }

while (line.isNotEmpty())
{
val colonIndex = line.indexOf(':')
if (colonIndex > 0) {
val key = line.substring(0, colonIndex).trim().lowercase()
val value = line.substring(colonIndex + 1).trim()
headers[key] = value
}
line = readLine()
}

- Place the { below the params of an if statement. As an example:
  //Pull from context bank if no page keys are set.
  if(pageKey.isEmpty() && pageKeyList.isEmpty())
  {
  contextWindow = ContextBank.copyBankedContextWindow() ?: ContextWindow()
  }

                else
                {
                    //Pull multiple pages of global context from the bank if more than one key was provided.
                    if(pageKeyList.isNotEmpty())
                    {
                        //Populate the mini bank for multi page key setup.
                        for(page in pageKeyList)
                        {
                            val pagedContext = ContextBank.getContextFromBank(page)
                            miniContextBank.contextMap[page] = pagedContext
                        }
                    }

                    contextWindow = ContextBank.getContextFromBank(pageKey)
                }
            }

- All functions must contain proper kdoc strings explaining each parameter, and 
all important details, footguns, or other complex concepts that need an explanation
to understand properly. As an example:
  /**
    * Sets the system prompt to be used by the AI model. This is a prompt that should provide information
    * about what the AI model should generate. If the AI does not support native json input and output,
    * this function will also use prompt injection to attempt to force json input and output.
    *
    * @param prompt The prompt to be used by the AI model.
    *
    * @since The system prompt should be set last due to it's use of prompt injection. Ensure the PCP context
    * and json inputs and outputs have been defined before calling this.
    *
    * @see setJsonInput
    * @see setJsonOutput
    * @see PcpContext
    *
    * @return This Pipe object for method chaining.
      */
      fun setSystemPrompt(prompt: String): Pipe

- Functions must have proper body comments explaining in good detail what exactly is
happening in them. As an example:
  /**
    * Internal multimodal execution logic shared by both execute methods
      */
      private suspend fun executeMultimodal(inputContent: MultimodalContent): MultimodalContent = coroutineScope{

      //Trace the start of this pipe, and print out the value of the output of the previous pipe or initial input.
      trace(TraceEventType.PIPE_START, TracePhase.INITIALIZATION, inputContent)

      //Bind this pipe to the content object as we pass it through for usage later if needed.
      inputContent.currentPipe = this@Pipe

      /**
        * Duplicate content to create a safe snapshot if desired. This is useful for branch failures
        * and walking back in time in the event of the llm transforming the text output into a refusal
        * or otherwise broken state.
          */
          if(inputContent.useSnapshot)
          {
          inputContent.metadata["snapshot"] = inputContent.copyMultimodal() as Any
          }

      //Get rid of model reasoning to prevent messed up token counts later.
      inputContent.modelReasoning = ""

      try {
      /**
      * Pull from global context if designated defaulting to the loaded bank, and using a paged bank value
      * if the page key was ever set.
      */
      trace(TraceEventType.CONTEXT_PULL, TracePhase.CONTEXT_PREPARATION)

           if(readFromGlobalContext && !readFromPipelineContext)
           {
               //Pull from context bank if no page keys are set.
               if(pageKey.isEmpty() && pageKeyList.isEmpty())
               {
                   contextWindow = ContextBank.copyBankedContextWindow() ?: ContextWindow()
               }

               else
               {
                   //Pull multiple pages of global context from the bank if more than one key was provided.
                   if(pageKeyList.isNotEmpty())
                   {
                       //Populate the mini bank for multi page key setup.
                       for(page in pageKeyList)
                       {
                           val pagedContext = ContextBank.getContextFromBank(page)
                           miniContextBank.contextMap[page] = pagedContext
                       }
                   }

                   contextWindow = ContextBank.getContextFromBank(pageKey)
               }
           }

           /**
            * Otherwise try to pull from the parent pipeline's context if set. This overrides pulling from global
            * context, and also overrides and overwrites any manual context setting if enabled.
            */
           else if(readFromPipelineContext)
           {
               contextWindow = pipelineRef?.context ?: ContextWindow()
           }

           /**
            * If enabled, apply the pre-validation function to the context window before the user prompt is merged.
            * This allows for context window modifications that are not dependent on the user prompt. This may be desired
            * when very granular, or specific modifications need to be made and always applied, regardless of the function
            * that might be calling this pipe or pipeline, or any other events that might be occurring prior to the context
            * window becoming fully settled after all external pulls, or retrieval has occurred.
            */
           if(preValidationFunction != null)
           {
               trace(TraceEventType.VALIDATION_START, TracePhase.PRE_VALIDATION,
                     metadata = mapOf("contextText" to contextWindow.toString()))
               val deferredContextResult : Deferred<ContextWindow> = async {
                   preValidationFunction?.invoke(contextWindow) ?: contextWindow
               }
               contextWindow = deferredContextResult.await()
               trace(TraceEventType.VALIDATION_SUCCESS, TracePhase.PRE_VALIDATION)
           }

           /**
            * Execute the mini bank version of pre-validation if enabled. This allows the pipe to address
            * situations where multiple banked context pages need to be evaluated prior to our pipe's llm
            * api call.
            */
           if(preValidationMiniBankFunction !=  null)
           {
               trace(TraceEventType.VALIDATION_START, TracePhase.PRE_VALIDATION,
                   metadata = mapOf("miniBankText" to miniContextBank.toString(),
                       "functionName" to preValidationMiniBankFunction.toString()))

               val deferredMiniBankResult : Deferred<MiniBank> = async {
                   preValidationMiniBankFunction?.invoke(miniContextBank) ?: miniContextBank
               }

               miniContextBank = deferredMiniBankResult.await()
               trace(TraceEventType.VALIDATION_SUCCESS, TracePhase.PRE_VALIDATION)
           }


           /**
            * If enabled, use the model's truncation settings to automatically truncate the context and lorebook to fit
            * the correct token budget. Lorebook key selection is typically done using the user prompt.
            * However, each provider module may use different methods for handling automatic truncation.
            * Be sure to look at each module to learn how it is addressing internal truncation.
            */
           if(autoTruncateContext)
           {
               trace(TraceEventType.CONTEXT_TRUNCATE, TracePhase.CONTEXT_PREPARATION,
                     metadata = mapOf(
                         "contextWindowSize" to contextWindowSize,
                         "truncateAsString" to truncateContextAsString,
                         "contextWindowTruncation" to contextWindowTruncation.name
                     ))
               truncateModuleContext()
           }

           // Merge multimodal input with injected content
           val baseContent = if (multimodalInput.text.isNotEmpty() || multimodalInput.binaryContent.isNotEmpty()) {
               val merged = MultimodalContent(multimodalInput.text, multimodalInput.binaryContent.toMutableList(), multimodalInput.terminatePipeline)
               merged.addText(inputContent.text)
               merged
           } else {
               inputContent
           }
           
           // Build full prompt with correct ordering: userPrompt -> user content -> context
           var fullPrompt = if(userPrompt.isNotEmpty()) {"${userPrompt}\n\n${baseContent.text}"} else{baseContent.text}
           
           /**
            * Context can be auto-injected after user content to maintain proper ordering
            */
           if(autoInjectContext)
           {
               //Default to standard context window if multiple keys are not present.
               if(pageKeyList.isEmpty())
               {
                   fullPrompt = "${fullPrompt}\n\n${serialize(contextWindow)}"
               }

               //Default to multiple page configuration if multiple page keys were supplied.
               else
               {
                   fullPrompt = "${fullPrompt}\n\n${serialize(miniContextBank)}"
               }
           }

           val processedContent = MultimodalContent(fullPrompt, baseContent.binaryContent.toMutableList(), baseContent.terminatePipeline)

           //Call pre-invoke function to test if we need to optionally skip over this pipe.
           if(preInvokeFunction  != null)
           {
               trace(TraceEventType.PRE_INVOKE, TracePhase.PRE_VALIDATION,
                   metadata = mapOf("invokeFunctionName" to preInvokeFunction.toString()))

               var result : Deferred<Boolean> = async {
                   var internalInvokeResult = false
                   preInvokeFunction?.invoke(inputContent) ?: false
               }

               //Exit if the pre-invoke function returns true indicating we should skip over this pipe.
               val invokeStatus = result.await()

               if(invokeStatus)
               {
                   trace(TraceEventType.PRE_INVOKE, TracePhase.PRE_VALIDATION,
                       metadata = mapOf("exitingInvoke" to preInvokeFunction.toString()))

                   return@coroutineScope inputContent
               }

           }

           // Trace the full prompt input
           trace(TraceEventType.PIPE_START, TracePhase.EXECUTION, processedContent, 
                 metadata = mapOf("fullPrompt" to fullPrompt))

           /**Bind this pipe's context window into the multimodal object context window to make it visible to
            * native functions. This allows the transformation function, and validator function to apply modifications
            * to the context
            */
           processedContent.context = contextWindow
           processedContent.workspaceContext = miniContextBank

           countTokens(true, processedContent) //Count input tokens.

           /**
            * Call generateContent() to invoke the loaded AI api with multimodal support.
            */
           trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION, processedContent)
           val result : Deferred<MultimodalContent> = async {
               generateContent(processedContent)
           }

           var generatedContent = result.await()
           trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, generatedContent)

           countTokens(false, generatedContent) //Count tokens the model generated.

           /**
            * If a validator pipe is supplied it can be invoked prior to the validation function.
            */
           var validatorPipeContent = generatedContent
           if(validatorPipe != null)
           {
               trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.VALIDATION)
               try {
                   validatorPipe!!.init()
                   if (tracingEnabled) {
                       validatorPipe!!.enableTracing(traceConfig)
                       validatorPipe!!.currentPipelineId = currentPipelineId
                   }
                   val validatorPipeResult : Deferred<MultimodalContent> = async {
                       validatorPipe?.execute(generatedContent) ?: MultimodalContent()
                   }
                   validatorPipeContent = validatorPipeResult.await()
               } catch (e: Exception) {
                   trace(TraceEventType.PIPE_FAILURE, TracePhase.VALIDATION, generatedContent, error = e)
                   validatorPipeContent = generatedContent
               }
           }

           if(!validatorPipeContent.shouldTerminate())
           {
               /**
                * Execute validation function if provided.
                */
               if (validatorFunction != null)
               {
                   trace(TraceEventType.VALIDATION_START, TracePhase.VALIDATION, generatedContent,
                         metadata = mapOf("inputText" to generatedContent.text))
                   val validatorResult : Deferred<Boolean> = async {
                       validatorFunction?.invoke(generatedContent) ?: true
                   }

                   //Validation passed. Continue to transformation.
                   if(validatorResult.await())
                   {
                       trace(TraceEventType.VALIDATION_SUCCESS, TracePhase.VALIDATION, generatedContent)
                       //Invoke transformation pipe if provided.
                       if (transformationPipe != null)
                       {
                           trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.TRANSFORMATION)
                           try {
                               transformationPipe!!.init()
                               if (tracingEnabled) {
                                   transformationPipe!!.enableTracing(traceConfig)
                                   transformationPipe!!.currentPipelineId = currentPipelineId
                               }
                               val transformPipeResult : Deferred<MultimodalContent> = async {
                                   transformationPipe?.execute(generatedContent) ?: generatedContent
                               }
                               generatedContent = transformPipeResult.await()
                           } catch (e: Exception) {
                               trace(TraceEventType.PIPE_FAILURE, TracePhase.TRANSFORMATION, generatedContent, error = e)
                               // Continue with original content if transformation pipe fails
                           }
                       }

                       /**
                        * Apply transformation function if provided.
                        */
                       val finalResult = if (transformationFunction != null)
                       {
                           trace(TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION, generatedContent,
                                 metadata = mapOf("inputText" to generatedContent.text))
                           val transformed = transformationFunction?.invoke(generatedContent) ?: generatedContent
                           trace(TraceEventType.TRANSFORMATION_SUCCESS, TracePhase.TRANSFORMATION, transformed,
                                 metadata = mapOf("outputText" to transformed.text))
                           transformed
                       } else generatedContent

                       if(!finalResult.context.isEmpty())
                       {
                           /**If the transformation function has adjusted any context values, rewrite the pipe's internal
                            * context window to allow us to update the context window going forward. We need to do it
                            * this way because the validator and transformation function system does not provide a mechanism
                            * to access the actual pipe object that is invoking them.
                            */
                           contextWindow = finalResult.context
                       }

                       //Merge in context window changes if enabled.
                       if(updatePipelineContextOnExit)
                       {
                           pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                       }

                       trace(TraceEventType.PIPE_SUCCESS, TracePhase.CLEANUP, finalResult,
                             metadata = mapOf("outputText" to finalResult.text))
                       return@coroutineScope finalResult
                   }
                   else
                   {
                       trace(TraceEventType.VALIDATION_FAILURE, TracePhase.VALIDATION, generatedContent)
                   }
               }
               else
               {
                   // No validation function, continue to transformation
                   if (transformationPipe != null)
                   {
                       trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.TRANSFORMATION)
                       try {
                           transformationPipe!!.init()
                           if (tracingEnabled) {
                               transformationPipe!!.enableTracing(traceConfig)
                               transformationPipe!!.currentPipelineId = currentPipelineId
                           }
                           val transformPipeResult : Deferred<MultimodalContent> = async {
                               transformationPipe?.execute(generatedContent) ?: generatedContent
                           }
                           generatedContent = transformPipeResult.await()
                       } catch (e: Exception) {
                           trace(TraceEventType.PIPE_FAILURE, TracePhase.TRANSFORMATION, generatedContent, error = e)
                           // Continue with original content if transformation pipe fails
                       }
                   }

                   val finalResult = if (transformationFunction != null)
                   {
                       trace(TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION, generatedContent,
                             metadata = mapOf("inputText" to generatedContent.text))
                       val transformed = transformationFunction?.invoke(generatedContent) ?: generatedContent
                       trace(TraceEventType.TRANSFORMATION_SUCCESS, TracePhase.TRANSFORMATION, transformed,
                             metadata = mapOf("outputText" to transformed.text))
                       transformed
                   } else generatedContent

                   if(!finalResult.context.isEmpty())
                   {
                       contextWindow = finalResult.context
                   }

                   if(updatePipelineContextOnExit)
                   {
                       pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                   }

                   trace(TraceEventType.PIPE_SUCCESS, TracePhase.CLEANUP, finalResult,
                         metadata = mapOf("outputText" to finalResult.text))
                   return@coroutineScope finalResult
               }

               //Execute branch pipe if provided.
               if (branchPipe != null)
               {
                   trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.POST_PROCESSING)
                   try {
                       // Initialize and setup branch pipe
                       branchPipe!!.init()
                       if (tracingEnabled) {
                           branchPipe!!.enableTracing(traceConfig)
                           branchPipe!!.currentPipelineId = currentPipelineId
                       }
                       
                       val branchPipeResult : Deferred<MultimodalContent> = async {
                           branchPipe?.execute(generatedContent) ?: generatedContent
                       }
                       val branchResult = branchPipeResult.await()
                       
                       //If branch pipe allows continuation, continue pipeline.
                       if(!branchResult.shouldTerminate())
                       {
                           //Merge in context window changes if enabled.
                           if(updatePipelineContextOnExit)
                           {
                               pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                           }


                           return@coroutineScope branchResult
                       }
                       
                       generatedContent = branchResult
                   } catch (e: Exception) {
                       trace(TraceEventType.PIPE_FAILURE, TracePhase.POST_PROCESSING, generatedContent, error = e)
                       // Branch pipe failed, continue to failure function
                   }
               }

               //Invoke failure function if provided.
               if (onFailure != null) {
                   trace(TraceEventType.VALIDATION_FAILURE, TracePhase.POST_PROCESSING,
                         metadata = mapOf("originalText" to processedContent.text, "failedText" to generatedContent.text))
                   val branchResult: Deferred<MultimodalContent> = async {
                       onFailure?.invoke(processedContent, generatedContent) ?: generatedContent
                   }

                   var failureResult = branchResult.await()
                   
                   //If failure function allows continuation, continue pipeline.
                   if(!failureResult.shouldTerminate())
                   {
                       //Merge in context window changes if enabled.
                       if(updatePipelineContextOnExit)
                       {
                           pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                       }

                       //Invoke transformation pipe if failure pipe was valid if able.
                       if(transformationPipe != null)
                       {
                           val transformResult: Deferred<MultimodalContent> = async {
                             transformationPipe?.executeMultimodal(failureResult) ?: failureResult
                           }

                           failureResult = transformResult.await()
                       }


                       if(transformationFunction != null)
                       {
                           failureResult = transformationFunction?.invoke(failureResult) ?: failureResult
                       }

                       //todo: Finish this trace output.
                       trace(TraceEventType.PIPE_SUCCESS, TracePhase.TRANSFORMATION,
                           metadata = mapOf("output" to "${failureResult.text}"))

                       return@coroutineScope failureResult
                   }
               }
           }

           /**
            * Pipeline termination - return terminated content to signal pipeline failure.
            */
           trace(TraceEventType.PIPE_FAILURE, TracePhase.CLEANUP, inputContent)
           return@coroutineScope MultimodalContent()

      } catch (e: Exception) {
      trace(TraceEventType.PIPE_FAILURE, TracePhase.CLEANUP, inputContent, error = e)
      return@coroutineScope MultimodalContent("")
      }

  }


When dealing with any funnctions that equal something fun myFun() = 
You must place the { to the right of the = because kotlin syntax requires it.

fun myFun = { //function body logic goes here
}

Else statements should be one line below the end of } except in cases where it's literally not allowed due to the
behavior of the compiler or languge syntax of kotlin.

EX: if(thing)
    {
        doThing()
    }

    else
    {
        doOtherThing()
    }

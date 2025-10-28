package com.TTT.Config

import com.TTT.Util.getHomeFolder
import java.util.UUID

object TPipeConfig
{
    var configDir = "${getHomeFolder()}/.tpipe" //Defines where TPipe looks for persisting lorebooks, and other settings.
    var instanceID = UUID.randomUUID().toString() //Defines unique name to avoid multiple instances of TPipe crashing.
}
tasks.register("printEnv") {
    doLast {
        val akid = System.getenv("AWS_ACCESS_KEY_ID")
        val sak = System.getenv("AWS_SECRET_ACCESS_KEY")
        val bml = System.getenv("BEDROCK_MANTLE_LIVE_TEST")
        val bmr = System.getenv("BEDROCK_MANTLE_REGION")
        println("PRINT_ENV_BEGIN")
        println("AWS_ACCESS_KEY_ID length=${akid?.length ?: -1}")
        println("AWS_SECRET_ACCESS_KEY length=${sak?.length ?: -1}")
        println("BEDROCK_MANTLE_LIVE_TEST=$bml")
        println("BEDROCK_MANTLE_REGION=$bmr")
        println("PRINT_ENV_END")
    }
}

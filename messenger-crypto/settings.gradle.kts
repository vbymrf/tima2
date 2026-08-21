rootProject.name = "messenger-crypto"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // mavenLocal ПЕРЕД mavenCentral — намеренно, и это не «на всякий случай».
        // Форки KyberKotlin и KeccakKotlin из ../third-party публикуются под теми
        // же координатами, что апстрим (asia.hombre:kyber, asia.hombre:keccak),
        // и отличаются ровно включёнными таргетами Apple. Апстрим их не
        // публикует, поэтому при обратном порядке iOS не соберётся, а сообщение
        // об ошибке будет про отсутствующий вариант, а не про порядок репозиториев.
        //
        // Собрать форки: ./gradlew publishToMavenLocal в third-party/KeccakKotlin,
        // затем в third-party/KyberKotlin (kyber зависит от keccak 2.1.1).
        mavenLocal()
        mavenCentral()
    }
}

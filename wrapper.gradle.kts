import org.gradle.api.tasks.wrapper.Wrapper

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "9.5.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
}

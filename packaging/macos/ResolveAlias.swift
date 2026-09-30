import Foundation

let url = URL(fileURLWithPath: CommandLine.arguments[1])
let target = try URL(resolvingAliasFileAt: url, options: [.withoutUI, .withoutMounting])
print(target.path)

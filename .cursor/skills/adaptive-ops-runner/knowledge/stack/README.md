# Stack rules

One file per tool, named after the tool in lowercase: `go.md`, `gradle.md`,
`docker.md`, `node.md`, `python.md`. Created by the journalist on first need.

A rule belongs here when it is about the tool and nothing else — its cache, its
daemon, its toolchain resolution, its flags, its exit-code conventions. If the
text names a path, a package or a port from the repository, it is not a stack
rule; it goes to `project/rules.md`.

The borderline case is a rule that would be portable except for one hard-coded
path. Rewrite it so the path is described rather than named, and keep it here.
When that cannot be done honestly, let it go to the project file.

These files travel with the skill. A new repository using the same toolchain
inherits them and does not relearn the same lesson.

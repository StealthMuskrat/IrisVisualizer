# Editor foundation

Windows x64 / Java 25 only. Native previews are disabled.

Build with Gradle 9.5.1: `gradle -p editor workspaceTest run`. The first shell provides the five File actions, a resource list, byte-preserving folder/ZIP workspace and read-only JSON inspection. Save/export do not depend on preview validation.

Schema-driven editing, audited editable fields, typed resource operations, image viewing, unsaved-close handling and Windows-only distribution packaging are unfinished. New currently creates an empty workspace, not a schema-complete Iris starter pack. The GUI build and visual verification remain pending; workspace contracts have passed locally.

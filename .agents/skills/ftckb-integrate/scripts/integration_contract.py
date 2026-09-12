"""Shared project-integration protocol and explicit profile selection."""


class IntegrationError(Exception):
    pass


def versions(config):
    fields=("schemaVersion","kernelSchemaVersion","integrationVersion")
    if not isinstance(config,dict) or any(type(config.get(field)) is not int for field in fields):
        raise IntegrationError("Protocol versions must be explicit integers")
    values=tuple(config[field] for field in fields)
    if values not in ((1,1,1),(2,2,2)):
        raise IntegrationError("Unsupported or mixed protocol versions; expected all 1 or all 2")
    return {field:config[field] for field in fields}


def capabilities(manifest):
    # Releases before the capability manifest use the original v1 protocol.
    if manifest is None:
        return {"schemaVersion":1,"kernelSchemaVersion":1,"integrationVersion":1}
    if not isinstance(manifest,dict):
        raise IntegrationError("Integration capability manifest must be an object")
    return versions({"schemaVersion":manifest.get("projectSchemaVersion"),
                     "kernelSchemaVersion":manifest.get("kernelSchemaVersion"),
                     "integrationVersion":manifest.get("integrationVersion")})


def normalize_profiles(values):
    if not isinstance(values,list) or any(not isinstance(value,str) for value in values):
        raise IntegrationError("Select an explicit profiles list or --generic-profile")
    if len(set(values))!=len(values):
        raise IntegrationError("Duplicate profiles are not allowed")
    supported={"rookiebot","simple-opmode","command-based","ftclib-command"}
    unknown=set(values)-supported
    if unknown:
        raise IntegrationError("Unknown profiles: "+", ".join(sorted(unknown)))
    normalized=set(values)
    if "rookiebot" in normalized:
        normalized.add("simple-opmode")
    if "ftclib-command" in normalized:
        normalized.add("command-based")
    if {"simple-opmode","command-based"}<=normalized:
        raise IntegrationError("simple-opmode and command-based profiles are mutually exclusive")
    return sorted(normalized)


def profile_args(config):
    profiles=normalize_profiles(config.get("profiles"))
    return [part for profile in profiles for part in ("--profile",profile)] if profiles else ["--generic-profile"]

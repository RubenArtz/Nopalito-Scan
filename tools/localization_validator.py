"""Repository localization audit. Report existing findings; fail only new findings in A/B files."""
from pathlib import Path
import argparse, re, sys

ROOT = Path(__file__).resolve().parents[1]
AB_FILES = {
    Path("app/src/main/java/nopalito/app/ui/screens/debug/VariantComparisonDialog.kt"),
    Path("app/src/main/java/nopalito/app/ui/screens/document/DocumentScreen.kt"),
}
LEGACY = {
    Path("app/src/test/java/nopalito/app/screens/tools/PdfProtectSmokeTest.java"),
    Path("app/src/test/java/nopalito/app/screens/export/ExportArtifactMapperTest.kt"),
    Path("app/src/main/java/nopalito/app/ui/screens/tools/deletepages/DeletePagesModels.kt"),
    Path("app/src/main/java/nopalito/app/ui/screens/tools/deletepages/DeletePagesViewModel.kt"),
    Path("app/src/main/java/nopalito/app/ui/screens/tools/organizer/OrganizerModels.kt"),
    Path("app/src/main/java/nopalito/app/ui/screens/tools/organizer/OrganizerViewModel.kt"),
    Path("app/src/main/java/nopalito/app/ui/screens/cloud/viewmodel/PcLinkViewModel.kt"),
    Path("app/src/test/java/nopalito/app/platform/crypto/DocxEncryptorTest.java"),
}
SPANISH = re.compile(r"[áéíóúñÁÉÍÓÚÑ]|(?:candidato|región|orientación|resultado|guardar|cancelar|eliminar|página|cámara)", re.I)
HARDCODED = re.compile(r'\b(?:Text|contentDescription)\s*\(\s*"')

def scan(path):
    text = path.read_text(encoding="utf-8")
    findings = []
    for number, line in enumerate(text.splitlines(), 1):
        if path.suffix in {".kt", ".java"} and HARDCODED.search(line):
            findings.append((number, "hardcoded-user-facing-string", line.strip()))
        if path.suffix == ".md" and SPANISH.search(line):
            findings.append((number, "technical-documentation-language", line.strip()))
    return findings

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("report", "fail"), default="report")
    args = parser.parse_args()
    roots = [ROOT / "app/src/main/java", ROOT / "app/src/test", ROOT / "app/src/androidTest", ROOT / "docs"]
    findings = []
    for base in roots:
        if base.exists():
            for path in base.rglob("*"):
                if path.is_file() and path.suffix in {".kt", ".java", ".md"}:
                    findings += [(path.relative_to(ROOT), *item) for item in scan(path)]
    for path in sorted(ROOT.glob("app/src/main/res/values/strings.xml")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if SPANISH.search(line):
                findings.append((path.relative_to(ROOT), number, "base-resource-language", line.strip()))
    print("Localization validation report")
    for path, number, category, line in findings:
        state = "legacy" if path in LEGACY else ("new" if path in AB_FILES or path.as_posix().startswith("docs/") else "existing")
        print(f"{path}:{number}: {category}: {state}: {line}")
    new = [item for item in findings if item[0] in AB_FILES or item[0].as_posix().startswith("docs/")]
    if args.mode == "fail" and new:
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())

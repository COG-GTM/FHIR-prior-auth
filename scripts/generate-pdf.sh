#!/bin/bash
# =============================================================================
# CMS-0057-F Integration Architecture — Markdown to PDF Generator
# =============================================================================
#
# This script converts the CMS-0057-F Integration Architecture Markdown
# document into a styled PDF with rendered Mermaid diagrams.
#
# Prerequisites:
#   npm install -g md-to-pdf
#
# Usage:
#   ./scripts/generate-pdf.sh
#
# Output:
#   docs/CMS-0057-F-Integration-Architecture.pdf
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DOCS_DIR="$PROJECT_ROOT/docs"
INPUT_FILE="$DOCS_DIR/CMS-0057-F-Integration-Architecture.md"
OUTPUT_FILE="$DOCS_DIR/CMS-0057-F-Integration-Architecture.pdf"
CONFIG_FILE="$SCRIPT_DIR/md-to-pdf-config.json"

echo "============================================="
echo " CMS-0057-F Architecture Document Generator"
echo "============================================="
echo ""

# Check that the input file exists
if [ ! -f "$INPUT_FILE" ]; then
    echo "ERROR: Input file not found: $INPUT_FILE"
    exit 1
fi

# ---------------------------------------------------------------------------
# Option 1: md-to-pdf (preferred — handles Mermaid natively via Puppeteer)
# ---------------------------------------------------------------------------
if command -v md-to-pdf &> /dev/null || npx --yes md-to-pdf --version &> /dev/null 2>&1; then
    echo "[INFO] Using md-to-pdf for conversion (Mermaid diagrams rendered natively)..."
    echo ""

    if [ -f "$CONFIG_FILE" ]; then
        echo "[INFO] Using config: $CONFIG_FILE"
        cd "$DOCS_DIR"
        npx --yes md-to-pdf \
            "CMS-0057-F-Integration-Architecture.md" \
            --config-file "$CONFIG_FILE"
    else
        echo "[WARN] Config file not found, using defaults..."
        cd "$DOCS_DIR"
        npx --yes md-to-pdf \
            "CMS-0057-F-Integration-Architecture.md"
    fi

    if [ -f "$OUTPUT_FILE" ]; then
        echo ""
        echo "[SUCCESS] PDF generated: $OUTPUT_FILE"
        echo "[INFO]    File size: $(du -h "$OUTPUT_FILE" | cut -f1)"
    else
        echo "[ERROR] PDF generation failed — output file not created."
        exit 1
    fi

# ---------------------------------------------------------------------------
# Option 2: pandoc with wkhtmltopdf or weasyprint
# ---------------------------------------------------------------------------
elif command -v pandoc &> /dev/null; then
    echo "[INFO] md-to-pdf not found. Falling back to pandoc..."
    echo "[WARN] Mermaid diagrams will appear as code blocks (not rendered)."
    echo ""

    PDF_ENGINE=""
    if command -v wkhtmltopdf &> /dev/null; then
        PDF_ENGINE="wkhtmltopdf"
    elif command -v weasyprint &> /dev/null; then
        PDF_ENGINE="weasyprint"
    fi

    if [ -n "$PDF_ENGINE" ]; then
        echo "[INFO] Using PDF engine: $PDF_ENGINE"
        pandoc "$INPUT_FILE" \
            -o "$OUTPUT_FILE" \
            --pdf-engine="$PDF_ENGINE" \
            --metadata title="CMS-0057-F Compliance: BFD-to-PAS Integration Architecture" \
            -V geometry:margin=1in \
            -V fontsize=11pt
    else
        echo "[INFO] No HTML-based PDF engine found. Using LaTeX engine..."
        pandoc "$INPUT_FILE" \
            -o "$OUTPUT_FILE" \
            -V geometry:margin=1in \
            -V fontsize=11pt \
            --toc
    fi

    if [ -f "$OUTPUT_FILE" ]; then
        echo ""
        echo "[SUCCESS] PDF generated: $OUTPUT_FILE"
        echo "[INFO]    File size: $(du -h "$OUTPUT_FILE" | cut -f1)"
        echo "[NOTE]    Mermaid diagrams are not rendered (shown as code blocks)."
        echo "          For rendered diagrams, install md-to-pdf:"
        echo "            npm install -g md-to-pdf"
    else
        echo "[ERROR] PDF generation failed."
        exit 1
    fi

# ---------------------------------------------------------------------------
# Option 3: No tools available
# ---------------------------------------------------------------------------
else
    echo "[ERROR] No PDF generation tools found."
    echo ""
    echo "Install one of the following:"
    echo ""
    echo "  Option A (Recommended — renders Mermaid diagrams):"
    echo "    npm install -g md-to-pdf"
    echo ""
    echo "  Option B (Pandoc — diagrams shown as code blocks):"
    echo "    sudo apt-get install pandoc wkhtmltopdf"
    echo ""
    echo "The Markdown file is still available at:"
    echo "  $INPUT_FILE"
    echo ""
    echo "It renders with full Mermaid diagrams on GitHub."
    exit 1
fi

echo ""
echo "Done."

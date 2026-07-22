# ConveniPayment44 の大規模データ版モデルを自動生成するスクリプト

function Generate-LargeModel($templateName, $outputName, $count) {
    if (-not (Test-Path "resources/$templateName")) {
        Write-Error "Template file resources/$templateName not found."
        return
    }

    $invoices = 1..$count | ForEach-Object {
        $comp = 100000 + $_
        $cust = 200000 + $_
        $amt = 1000 + $_ * 100
        "        mk_invoice($comp, $cust, $amt)"
    }
    $invoiceBlock = $invoices -join ",`r`n"

    $template = Get-Content -Raw -Path "resources/$templateName"
    $pattern = 'pending_invoices : set of invoice := \{[\s\S]*?\};'
    $replacement = "pending_invoices : set of invoice := {`r`n" + $invoiceBlock + "`r`n    };"
    
    $newContent = [regex]::Replace($template, $pattern, $replacement)
    
    # クラス名が ConveniPayment44 のままであることを確認して出力
    $newContent | Set-Content -NoNewline -Path "resources/$outputName" -Encoding utf8
    Write-Host "Generated resources/$outputName with $count invoices."
}

# ConveniPayment44_bug_A_large.vdmpp を 500件で生成
Generate-LargeModel "ConveniPayment44_bug_A.vdmpp" "ConveniPayment44_bug_A_large.vdmpp" 500

# ConveniPayment44_bug_B_large.vdmpp を 500件で生成
Generate-LargeModel "ConveniPayment44_bug_B.vdmpp" "ConveniPayment44_bug_B_large.vdmpp" 500

# 正常系 ConveniPayment44_large.vdmpp を 500件で生成
Generate-LargeModel "ConveniPayment44.vdmpp" "ConveniPayment44_large.vdmpp" 500

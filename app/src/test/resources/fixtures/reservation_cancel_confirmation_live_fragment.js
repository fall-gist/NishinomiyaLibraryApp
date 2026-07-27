// if (document.all) {//IE
// begin 2022/06/20 MBP 陶 障害単票-HNCL2022_不具合票-001
// if (document.all || IS_EXPLORER_11) {// IE
if (document.all || IS_EXPLORER_11 || isEdge) {// IE
// end 2022/06/20 MBP 陶 障害単票-HNCL2022_不具合票-001
    if (0 != 1) {
        if (0 == 1) {
            rest = confirm("予約の取消を行います。よろしいですか？", ""); // 業務用
        } else {
            rest = lbConfirm("予約の取消を行います。よろしいですか？", "", "#F1F1FF"); // 業務用
        }
    } else {
        if (0 == 1) {
            rest = confirm("予約の取消を行います。よろしいですか？", ""); // 業務用
        } else {
            rest = lbConfirm1("予約の取消を行います。よろしいですか？", "", "#F1F1FF"); // 業務用
        }
    }
} else {//MF
    rest = window.confirm("予約の取消を行います。よろしいですか？");
}
if (rest) {
    okArray[okArray.length] = "OPACUSR001";
    submitFlg = false;
} else {
    // キャンセルされたら、処理を中断
    return cancelDialog();
}
// 選択メッセージ
// ---------------------------------------------------------------------
// ダイアログの確認結果をフォームに格納
// ---------------------------------------------------------------------
// OKボタンを押下されたメッセージコードを hidden に格納する
for (var i = 0; i < okArray.length; i++) {
    var newHidden = document.createElement("input");
    newHidden.type = "hidden";
    newHidden.name = OK_CODES_NAME;
    newHidden.value = okArray[i];
    document.prevRequestForm.appendChild(newHidden);
}

"""
Patched task1/train.py — modifications over nexgen's original:

  1. Adds an independent validation loader (val.pkl) used for picking the best
     epoch. Original code used the test set for per-epoch eval, which is a
     val/test leak; we keep training/val separate from the 1594 test set.

  2. After each epoch, computes val accuracy and tracks the best-so-far. At
     the end of training, prints BEST_EPOCH so the orchestration shell script
     can pick it up. Also writes a JSON summary at ./summary.json.

  3. predict_dump(epoch, out_json) — extends original predict() to dump
     per-method per-line predictions (compatible with the assemble step).

Drop this into nexgen/source/task1/ replacing the original train.py.
Expected data layout under data_folder (set by env DATA_FOLDER):

  DATA_FOLDER/
    TRAIN_data.pth.tar
    VAL_data.pth.tar
    TEST_data.pth.tar            # the 1594 test set, encoded
    vocab.pt
"""
import json
import os
import sys
import torch
import torch.backends.cudnn as cudnn
import torch.optim as optim
from torch.nn.utils.rnn import pack_padded_sequence
from tqdm import tqdm
from sklearn import metrics

from model import *  # noqa
from utils import *  # noqa
from dataset import EHDataset

# Resolve paths from env so the same script works for nexgen and retrain runs.
data_folder = os.environ.get("DATA_FOLDER", "./output")
ckpt_folder = os.environ.get("CKPT_FOLDER", "./checkpoints")
summary_path = os.environ.get("SUMMARY_PATH", "./summary.json")

word_map = torch.load(os.path.join(data_folder, "vocab.pt")).stoi

# Model parameters (unchanged from nexgen original)
n_classes = 1
emb_size = 128
word_rnn_size = 128
sentence_rnn_size = 128
word_rnn_layers = 1
sentence_rnn_layers = 1
word_att_size = 128
sentence_att_size = 128
dropout = 0.0
use_crf = False

# Training parameters
start_epoch = 0
batch_size = int(os.environ.get("BATCH_SIZE", "64"))
lr = 1e-3
workers = int(os.environ.get("WORKERS", "4"))
epochs = int(os.environ.get("EPOCHS", "20"))
print_freq = 200
checkpoint = None

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
cudnn.benchmark = True

os.makedirs(ckpt_folder, exist_ok=True)


def save_checkpoint(epoch, model, optimizer, word_map):
    state = {"epoch": epoch, "model": model, "optimizer": optimizer, "word_map": word_map}
    path = os.path.join(ckpt_folder, f"checkpoint_{epoch}.pth.tar")
    torch.save(state, path)


def main():
    global checkpoint, start_epoch, word_map

    if checkpoint is not None:
        checkpoint = torch.load(checkpoint)
        model = checkpoint["model"]
        optimizer = checkpoint["optimizer"]
        word_map = checkpoint["word_map"]
        start_epoch = checkpoint["epoch"] + 1
    else:
        model = HierarchicalAttentionNetwork(
            n_classes=n_classes,
            vocab_size=len(word_map),
            emb_size=emb_size,
            word_rnn_size=word_rnn_size,
            sentence_rnn_size=sentence_rnn_size,
            word_rnn_layers=word_rnn_layers,
            sentence_rnn_layers=sentence_rnn_layers,
            word_att_size=word_att_size,
            sentence_att_size=sentence_att_size,
            dropout=dropout,
        )
        optimizer = optim.Adam(
            params=filter(lambda p: p.requires_grad, model.parameters()), lr=lr
        )

    criterion = nn.BCELoss()
    model = model.to(device)
    criterion = criterion.to(device)

    train_loader = torch.utils.data.DataLoader(
        EHDataset(data_folder, "train"),
        batch_size=batch_size, shuffle=True, num_workers=workers,
    )
    # Independent VAL set; do NOT use TEST here.
    val_loader = torch.utils.data.DataLoader(
        EHDataset(data_folder, "val"),
        batch_size=batch_size, shuffle=False, num_workers=workers,
    )

    print(f"data_folder = {data_folder}")
    print(f"ckpt_folder = {ckpt_folder}")
    print(f"epochs={epochs}, batch_size={batch_size}, workers={workers}")
    print("Start training...")
    history = []
    best_val_acc = -1.0
    best_epoch = -1

    for epoch in range(start_epoch, epochs):
        train(train_loader, model, criterion, optimizer, epoch)
        save_checkpoint(epoch, model, optimizer, word_map)
        val_loss, val_acc = evaluate(model, val_loader, criterion, tag="val")
        history.append({"epoch": epoch, "val_loss": val_loss, "val_acc": val_acc})
        if val_acc > best_val_acc:
            best_val_acc = val_acc
            best_epoch = epoch
        # Persist after every epoch so an interrupted run still has the best so far.
        with open(summary_path, "w") as f:
            json.dump(
                {"best_epoch": best_epoch, "best_val_acc": best_val_acc, "history": history},
                f, indent=2,
            )
        print(f"[epoch {epoch}] val_acc={val_acc:.4f}  best_epoch_so_far={best_epoch} (acc {best_val_acc:.4f})")

    print(f"BEST_EPOCH={best_epoch}  BEST_VAL_ACC={best_val_acc:.4f}")


def train(train_loader, model, criterion, optimizer, epoch):
    losses = AverageMeter()
    accs = AverageMeter()
    model.train()
    for i, (documents, sentences_per_document, words_per_sentence, labels) in enumerate(train_loader):
        documents = documents.to(device)
        sentences_per_document = sentences_per_document.squeeze(1).to(device)
        words_per_sentence = words_per_sentence.to(device)
        labels = labels.permute(0, 2, 1).squeeze(-1).to(device)
        packed_labels = pack_padded_sequence(
            labels, sentences_per_document.tolist(), batch_first=True, enforce_sorted=False,
        )
        scores = model(documents, sentences_per_document, words_per_sentence)
        scores = scores.squeeze(-1)
        packed_scores = pack_padded_sequence(
            scores, sentences_per_document.tolist(), batch_first=True, enforce_sorted=False,
        )
        loss = criterion(packed_scores.data, packed_labels.data)

        optimizer.zero_grad()
        loss.backward()
        optimizer.step()

        predictions = scores.gt(0.5).float()
        res = []
        for j, length in enumerate(sentences_per_document.tolist()):
            truth = labels[j][:length]
            prediction = predictions[j][:length]
            res.append(1 if prediction.equal(truth) else 0)
        accuracy = sum(res) / len(res)
        losses.update(loss.item(), len(res))
        accs.update(accuracy, len(res))

        if i % print_freq == 0:
            print(
                f"Epoch: [{epoch}][{i}/{len(train_loader)}]\t"
                f"Loss {losses.val:.4f} ({losses.avg:.4f})\t"
                f"Accuracy {accs.val:.3f} ({accs.avg:.3f})"
            )


def evaluate(model, loader, criterion, tag="val"):
    losses = AverageMeter()
    accs = AverageMeter()
    model.eval()
    with torch.no_grad():
        for documents, sentences_per_document, words_per_sentence, labels in tqdm(loader, desc=f"Evaluating[{tag}]"):
            documents = documents.to(device)
            sentences_per_document = sentences_per_document.squeeze(1).to(device)
            words_per_sentence = words_per_sentence.to(device)
            labels = labels.permute(0, 2, 1).squeeze(-1).to(device)
            packed_labels = pack_padded_sequence(
                labels, sentences_per_document.tolist(), batch_first=True, enforce_sorted=False,
            )
            scores = model(documents, sentences_per_document, words_per_sentence)
            scores = scores.squeeze(-1)
            packed_scores = pack_padded_sequence(
                scores, sentences_per_document.tolist(), batch_first=True, enforce_sorted=False,
            )
            loss = criterion(packed_scores.data, packed_labels.data)
            predictions = scores.gt(0.5).float()
            res = []
            for j, length in enumerate(sentences_per_document.tolist()):
                truth = labels[j][:length]
                prediction = predictions[j][:length]
                res.append(1 if prediction.equal(truth) else 0)
            accuracy = sum(res) / len(res) if res else 0.0
            losses.update(loss.item(), len(res))
            accs.update(accuracy, len(res))
    print(f"\n *{tag} Loss: {losses.avg:.4f}\t Accuracy: {accs.avg:.4f}\n")
    return losses.avg, accs.avg


def predict(epoch):
    """Original predict: just prints metrics on test split."""
    test_loader = torch.utils.data.DataLoader(
        EHDataset(data_folder, "test"),
        batch_size=batch_size, shuffle=False, num_workers=workers,
    )
    model = torch.load(os.path.join(ckpt_folder, f"checkpoint_{epoch}.pth.tar"))["model"]
    model.eval()
    y_t, y_p, res = [], [], []
    with torch.no_grad():
        for documents, sentences_per_document, words_per_sentence, labels in tqdm(test_loader, desc="Evaluating"):
            documents = documents.to(device)
            sentences_per_document = sentences_per_document.squeeze(1).to(device)
            words_per_sentence = words_per_sentence.to(device)
            labels = labels.permute(0, 2, 1).squeeze(-1).to(device)
            scores = model(documents, sentences_per_document, words_per_sentence)
            scores = scores.squeeze(-1)
            predictions = scores.gt(0.5).float()
            for j, (length, _nums) in enumerate(zip(sentences_per_document.tolist(), words_per_sentence.tolist())):
                truth = labels[j][:length]
                prediction = predictions[j][:length]
                res.append(1 if prediction.equal(truth) else 0)
                y_t.extend(truth.tolist())
                y_p.extend(prediction.tolist())
    acc = sum(res) / len(res)
    print("Accuracy:", acc)
    print("Precision:", metrics.precision_score(y_t, y_p, zero_division=0))
    print("Recall:", metrics.recall_score(y_t, y_p, zero_division=0))
    print("F1:", metrics.f1_score(y_t, y_p, zero_division=0))


def predict_dump(epoch, out_json):
    """Inference on TEST_data + dump per-method per-line predictions to JSON.
    Output format: list of dicts:
        {"per_line_pred": [0,1,1,0,...], "per_line_truth": [...]}
    The order matches the test.pkl row order; downstream assembly maps these
    back to the 1594 sample dict by index.
    """
    test_loader = torch.utils.data.DataLoader(
        EHDataset(data_folder, "test"),
        batch_size=batch_size, shuffle=False, num_workers=workers,
    )
    model = torch.load(os.path.join(ckpt_folder, f"checkpoint_{epoch}.pth.tar"))["model"]
    model.eval()
    rows = []
    with torch.no_grad():
        for documents, sentences_per_document, words_per_sentence, labels in tqdm(test_loader, desc="predict_dump"):
            documents = documents.to(device)
            sentences_per_document = sentences_per_document.squeeze(1).to(device)
            words_per_sentence = words_per_sentence.to(device)
            labels = labels.permute(0, 2, 1).squeeze(-1).to(device)
            scores = model(documents, sentences_per_document, words_per_sentence).squeeze(-1)
            predictions = scores.gt(0.5).float()
            for j, length in enumerate(sentences_per_document.tolist()):
                rows.append({
                    "per_line_pred": [int(v) for v in predictions[j][:length].tolist()],
                    "per_line_truth": [int(v) for v in labels[j][:length].tolist()],
                })
    with open(out_json, "w") as f:
        json.dump(rows, f)
    print(f"dumped {len(rows)} method predictions to {out_json}")


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "train":
        main()
    elif mode == "test":
        predict(int(sys.argv[2]))
    elif mode == "predict_dump":
        # python train.py predict_dump <epoch> <out_json>
        predict_dump(int(sys.argv[2]), sys.argv[3])
    else:
        raise ValueError(f"unknown mode: {mode}")
